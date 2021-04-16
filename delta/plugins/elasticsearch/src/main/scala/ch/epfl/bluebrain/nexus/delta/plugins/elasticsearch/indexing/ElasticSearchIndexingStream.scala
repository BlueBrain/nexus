package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, IndexLabel}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.{CleanupStrategy, ProgressStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for an IndexingElasticSearchView
  */
final class ElasticSearchIndexingStream(
    client: ElasticSearchClient,
    indexingSource: IndexingSource,
    cache: ProgressesCache,
    config: ElasticSearchViewsConfig,
    projection: Projection[Unit]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends IndexingStream[IndexingElasticSearchView] {

  override def apply(
      view: ViewIndex[IndexingElasticSearchView],
      strategy: IndexingStream.Strategy[IndexingElasticSearchView]
  ): Stream[Task, Unit] = {
    val index = idx(view)
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        client.createIndex(index, Some(view.value.mapping), view.value.settings) >>
          handleCleanup(strategy.cleanup) >>
          handleProgress(strategy.progress, view.projectionId)
      }
      .flatMap { progress =>
        indexingSource(view.projectRef, progress.offset, view.resourceTag)
          .evalMapValue { eventExchangeValue =>
            // Creates a resource graph and metadata from the event exchange response
            ElasticSearchIndexingStreamEntry.fromEventExchange(eventExchangeValue)
          }
          .evalMapFilterValue {
            // Either delete or insert the document depending on filtering options
            case res if res.containsSchema(view.value.resourceSchemas) && res.containsTypes(view.value.resourceTypes) =>
              res.deleteOrIndex(
                index,
                view.value.includeMetadata,
                view.value.includeDeprecated,
                view.value.sourceAsText
              )
            case res if res.containsSchema(view.value.resourceSchemas)                                                =>
              res.delete(index).map(Some.apply)
            case _                                                                                                    =>
              Task.none
          }
          .runAsyncUnit { bulk =>
            // Pushes INDEX/DELETE Elasticsearch bulk operations
            IO.when(bulk.nonEmpty)(client.bulk(bulk))
          }
          .flatMap(Stream.chunk)
          .map(_.void)
          // Persist progress in cache and in primary store
          .persistProgressWithCache(
            progress,
            view.projectionId,
            projection,
            cache.put(view.projectionId, _),
            config.indexing.projection,
            config.indexing.cache
          )
      }
  }

  private def handleProgress(
      strategy: ProgressStrategy,
      projectionId: ViewProjectionId
  ): Task[ProjectionProgress[Unit]] =
    strategy match {
      case ProgressStrategy.Continue    =>
        projection.progress(projectionId)
      case ProgressStrategy.FullRestart =>
        cache.remove(projectionId) >>
          cache.put(projectionId, NoProgress) >>
          projection.recordProgress(projectionId, NoProgress).as(NoProgress)
    }

  private def handleCleanup(strategy: CleanupStrategy[IndexingElasticSearchView]): Task[Unit] =
    strategy match {
      case CleanupStrategy.NoCleanup     => Task.unit
      case CleanupStrategy.Cleanup(view) =>
        // TODO: We might want to delete the projection row too, but deletion is not implemented in Projection
        cache.remove(view.projectionId) >> client.deleteIndex(idx(view)).attempt.void
    }

  private def idx(view: ViewIndex[_]): IndexLabel =
    IndexLabel.unsafe(view.index)
}
