package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object ElasticSearchViewsIndexing {
  private val logger: Logger = Logger[ElasticSearchViewsIndexing.type]

  /**
    * Read elasticsearch events from event log and apply the given function for each of them
    * @param name      the name of the stream, must be unique
    * @param config    the config for the retry strategy
    * @param eventLog  the event log
    * @param onEvent   the function to apply for each event
    */
  def apply(
      name: String,
      config: ExternalIndexingConfig,
      views: ElasticSearchViews,
      onEvent: ElasticSearchViewEvent => Task[Unit]
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] =
    DaemonStreamCoordinator.run(
      name,
      streamTask = Task.delay(
        views.events(Offset.noOffset).evalMap { e => onEvent(e.event) }
      ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.retry, logger, name)
    )
}
