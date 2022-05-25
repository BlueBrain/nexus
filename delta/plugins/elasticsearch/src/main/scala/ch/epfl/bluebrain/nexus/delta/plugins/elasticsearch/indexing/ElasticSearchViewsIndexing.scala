package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.ElasticSearchViewCache
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object ElasticSearchViewsIndexing {
  private val logger: Logger = Logger[ElasticSearchViewsIndexing.type]

  /**
    * Populate the elasticsearch views cache from the event log
    */
  def populateCache(retry: RetryStrategyConfig, views: ElasticSearchViews, cache: ElasticSearchViewCache)(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[Unit] = {
    def onEvent = (event: ElasticSearchViewEvent) =>
      views
        .fetch(event.id, event.project)
        .redeemCauseWith(_ => IO.unit, res => cache.put(res.value.project, res.value.id, res))

    apply("ElasticSearchViewsIndex", retry, views, onEvent)
  }

  /**
    * Starts indexing streams from the event log
    */
  def startIndexingStreams(
      retry: RetryStrategyConfig,
      views: ElasticSearchViews,
      coordinator: ElasticSearchIndexingCoordinator,
      beforeRunning: Task[Unit] = Task.unit
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] = {
    def onEvent(event: ElasticSearchViewEvent) = coordinator.run(event.id, event.project, event.rev)
    apply("ElasticSearchIndexingCoordinatorScan", retry, views, onEvent, beforeRunning)
  }

  private def apply(
      name: String,
      retry: RetryStrategyConfig,
      views: ElasticSearchViews,
      onEvent: ElasticSearchViewEvent => Task[Unit],
      beforeRunning: Task[Unit] = Task.unit
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      stream = fs2.Stream.eval(beforeRunning) >> views.events(Offset.noOffset).evalMap { e => onEvent(e.event) },
      retryStrategy = RetryStrategy.retryOnNonFatal(retry, logger, name)
    )

}
