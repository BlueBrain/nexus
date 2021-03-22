package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object BlazegraphViewsIndexing {
  private val logger: Logger = Logger[BlazegraphViewsIndexing.type]

  /**
    * Read blazegraph events from event log and apply the given function for each of them
    * @param name      the name of the stream, must be unique
    * @param config    the config for the retry strategy
    * @param eventLog  the event log
    * @param onEvent   the function to apply for each event
    */
  def apply(
      name: String,
      config: ExternalIndexingConfig,
      views: BlazegraphViews,
      onEvent: BlazegraphViewEvent => Task[Unit]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      streamTask = Task.delay(
        views.events(Offset.noOffset).evalMap { e => onEvent(e.event) }
      ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.retry, logger, name)
    )

}
