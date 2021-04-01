package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews.CompositeViewsCache
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingCoordinator.CompositeIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewEvent
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object CompositeViewsIndexing {
  private val logger: Logger = Logger[CompositeViewsIndexing.type]

  /**
    * Deletes the Elasticsearch indices and Blazegraph namespaces for views which are not being used
    * (deprecated or older revisions)
    */
  def deleteNotUsedIndicesAndNamespaces(): Task[Unit] =
    Task.unit //TODO: to be implemented

  /**
    * Populate the composite views cache from the event log
    */
  def populateCache(retry: RetryStrategyConfig, views: CompositeViews, cache: CompositeViewsCache)(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      sc: Scheduler
  ): Task[Unit] = {
    def onEvent = (event: CompositeViewEvent) =>
      views
        .fetch(event.id, event.project)
        .redeemCauseWith(_ => IO.unit, res => cache.put(ViewRef(res.value.project, res.value.id), res))

    apply("CompositeViewsIndex", retry, views, onEvent)
  }

  /**
    * Starts indexing streams from the event log
    */
  def startIndexingStreams(
      retry: RetryStrategyConfig,
      views: CompositeViews,
      coordinator: CompositeIndexingCoordinator
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] = {
    def onEvent(event: CompositeViewEvent) = coordinator.run(event.id, event.project, event.rev)
    apply("CompositeIndexingCoordinatorScan", retry, views, onEvent)
  }

  private def apply(
      name: String,
      retry: RetryStrategyConfig,
      views: CompositeViews,
      onEvent: CompositeViewEvent => Task[Unit]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      stream = views.events(Offset.noOffset).evalMap { e => onEvent(e.event) },
      retryStrategy = RetryStrategy.retryOnNonFatal(retry, logger, name)
    )

}
