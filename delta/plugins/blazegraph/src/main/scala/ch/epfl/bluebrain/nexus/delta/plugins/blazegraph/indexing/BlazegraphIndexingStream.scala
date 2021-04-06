package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStream.{CleanupStrategy, ProgressStrategy}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingSource, IndexingStream}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionStream.{ChunkStreamOps, SimpleStreamOps}
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

  implicit private val cl: ClassLoader = getClass.getClassLoader

  override def apply(
      view: ViewIndex[IndexingBlazegraphView],
      strategy: IndexingStream.Strategy[IndexingBlazegraphView]
  ): Stream[Task, Unit] =
    Stream
      .eval {
        // Evaluates strategy and set/get the appropriate progress
        createNamespace(view.index) >>
          handleCleanup(strategy.cleanup) >>
          handleProgress(strategy.progress, view.projectionId)
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
              res.deleteOrIndex(view.value.includeMetadata, view.value.includeDeprecated).map(Some.apply)
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

  private def handleCleanup(strategy: CleanupStrategy[IndexingBlazegraphView]): Task[Unit] =
    strategy match {
      case CleanupStrategy.NoCleanup     => Task.unit
      case CleanupStrategy.Cleanup(view) =>
        // TODO: We might want to delete the projection row too, but deletion is not implemented in Projection
        cache.remove(view.projectionId) >> client.deleteNamespace(view.index).attempt.void
    }

  private def createNamespace(idx: String): Task[Unit] =
    for {
      props <- ClasspathResourceUtils.ioPropertiesOf("blazegraph/index.properties")
      _     <- client.createNamespace(idx, props)
    } yield ()

}
