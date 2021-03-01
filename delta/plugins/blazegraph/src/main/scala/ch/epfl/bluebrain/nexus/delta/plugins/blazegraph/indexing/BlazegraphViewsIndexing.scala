package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{moduleType, BlazegraphViewsCache}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.{StartCoordinator, StopCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object BlazegraphViewsIndexing {
  private val logger: Logger = Logger[BlazegraphViewsIndexing.type]

  /**
    * 1. Read blazegraph view events from the tag views table
    * 2. Fetch the latest state
    * 3. Index the state into a Distributed Data cache of [[BlazegraphView]]s
    * 4. Starts or stops the coordinator for a view depending on its deprecation status
    */
  def apply(
      config: ExternalIndexingConfig,
      eventLog: EventLog[Envelope[BlazegraphViewEvent]],
      index: BlazegraphViewsCache,
      views: BlazegraphViews,
      startCoordinator: StartCoordinator,
      stopCoordinator: StopCoordinator
  )(implicit as: ActorSystem[Nothing], sc: Scheduler) =
    StreamSupervisor(
      "BlazegraphViewsIndex",
      streamTask = Task.delay(
        eventLog
          .eventsByTag(moduleType, Offset.noOffset)
          .evalMapFilter { envelope =>
            views.fetch(envelope.event.id, envelope.event.project).attempt.map(_.toOption)
          }
          .evalTap { res =>
            index.put(ViewRef(res.value.project, res.value.id), res)
          }
          .evalMap {
            case res @ ResourceF(_, _, _, _, false, _, _, _, _, _, view: IndexingBlazegraphView) =>
              startCoordinator(res.as(view))
            case res @ ResourceF(_, _, _, _, true, _, _, _, _, _, view: IndexingBlazegraphView)  =>
              stopCoordinator(res.as(view))
            case _                                                                               => Task.unit
          }
      ),
      retryStrategy = RetryStrategy(
        config.retry,
        _ => true,
        RetryStrategy.logError(logger, "blazegraph views indexing")
      )
    )

}
