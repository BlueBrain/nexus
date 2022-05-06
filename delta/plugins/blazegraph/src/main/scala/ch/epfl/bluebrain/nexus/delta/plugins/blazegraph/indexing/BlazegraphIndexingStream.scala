package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricsConfig
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
  ): Task[Stream[Task, Unit]] = Task.delay {
    implicit val metricsConfig: KamonMetricsConfig = ViewIndex.metricsConfig(view, view.value.tpe.tpe)
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
          .evalMapFilterValue(_.writeOrNone(view.value))
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
          .enableMetrics
          .map(_.value)
      }
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
      case _                            =>
        Task.raiseError(
          new IllegalArgumentException(
            "Only `Continue` and `FullRestart` are valid progress strategies for blazegraph views."
          )
        )
    }

}
