package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.ProgressStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sdk.views.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.{Projection, ProjectionProgress}
import fs2.Stream
import monix.bio.{IO, Task}
import monix.execution.Scheduler

/**
  * Defines how to build a stream for an IndexingBlazegraphView
  */
final class BlazegraphIndexingStream(
    client: BlazegraphClient,
    indexingSource: IndexingSource,
    cache: ProgressesCache,
    config: BlazegraphViewsConfig,
    projection: Projection[Unit]
)(implicit cr: RemoteContextResolution, baseUri: BaseUri, sc: Scheduler)
    extends IndexingStream[IndexingBlazegraphView] {

  override def apply(
      view: ViewIndex[IndexingBlazegraphView],
      strategy: IndexingStream.ProgressStrategy
  ): Stream[Task, Unit] =
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        client.createNamespace(view.index) >> handleProgress(strategy, view.projectionId)
      }
      .flatMap { progress =>
        indexingSource(view.projectRef, progress.offset, view.resourceTag)
          .evalMapValue { eventExchangeValue =>
            // Creates a resource graph and metadata from the event exchange response
            BlazegraphIndexingStreamEntry.fromEventExchange(eventExchangeValue)
          }
          .evalMapFilterValue {
            // Either delete the named graph or insert triples to it depending on filtering options
            case res if res.containsSchema(view.value.resourceSchemas) && res.containsTypes(view.value.resourceTypes) =>
              res.deleteOrIndex(view.value.includeMetadata, view.value.includeDeprecated)
            case res if res.containsSchema(view.value.resourceSchemas)                                                =>
              res.delete().map(Some.apply)
            case _                                                                                                    =>
              Task.none
          }
          .runAsyncUnit { bulk =>
            // Pushes DROP/REPLACE queries to Blazegraph
            IO.when(bulk.nonEmpty)(client.bulk(view.index, bulk))
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
          .viewMetrics(view, view.value.tpe.tpe)
          .map(_.value)
      }

  private def handleProgress(
      strategy: ProgressStrategy,
      projectionId: ViewProjectionId
  ): Task[ProjectionProgress[Unit]] =
    strategy match {
      case ProgressStrategy.Continue    =>
        for {
          progress <- projection.progress(projectionId)
          _        <- cache.put(projectionId, progress)
        } yield progress
      case ProgressStrategy.FullRestart =>
        cache.remove(projectionId) >>
          cache.put(projectionId, NoProgress) >>
          projection.recordProgress(projectionId, NoProgress).as(NoProgress)
    }

}
