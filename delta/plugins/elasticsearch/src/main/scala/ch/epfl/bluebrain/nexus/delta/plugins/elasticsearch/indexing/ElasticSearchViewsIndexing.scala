package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.ElasticSearchViewCache
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.ElasticSearchIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object ElasticSearchViewsIndexing {
  private val logger: Logger = Logger[ElasticSearchViewsIndexing.type]

  /**
    * Deletes the Elasticsearch indices for views which are not being used (deprecated or older revisions)
    */
  def deleteNotUsedIndices(): Task[Unit] =
    Task.unit //TODO: to be implemented

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
        .redeemCauseWith(_ => IO.unit, res => cache.put(ViewRef(res.value.project, res.value.id), res))

    apply("ElasticSearchViewsIndex", retry, views, onEvent)
  }

  /**
    * Starts indexing streams from the event log
    */
  def startIndexingStreams(
      retry: RetryStrategyConfig,
      views: ElasticSearchViews,
      coordinator: ElasticSearchIndexingCoordinator
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] = {
    def onEvent(event: ElasticSearchViewEvent) = coordinator.run(event.id, event.project, event.rev)
    apply("ElasticSearchIndexingCoordinatorScan", retry, views, onEvent)
  }

  private def apply(
      name: String,
      retry: RetryStrategyConfig,
      views: ElasticSearchViews,
      onEvent: ElasticSearchViewEvent => Task[Unit]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      stream = views.events(Offset.noOffset).evalMap { e => onEvent(e.event) },
      retryStrategy = RetryStrategy.retryOnNonFatal(retry, logger, name)
    )

}
