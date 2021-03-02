package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.{moduleType, ElasticSearchViewCache}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingCoordinator.{StartCoordinator, StopCoordinator}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.{ElasticSearchViewEvent, ViewRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.StreamSupervisor
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object ElasticSearchViewsIndexing {
  private val logger: Logger = Logger[ElasticSearchViewsIndexing.type]

  /**
    * 1. Read elasticsearch view events from the tag views table
    * 2. Fetch the latest state
    * 3. Index the state into a Distributed Data cache of ElasticSearchViews
    * 4. Starts or stops the coordinator for a view depending on its deprecation status
    */
  def apply(
      config: ExternalIndexingConfig,
      eventLog: EventLog[Envelope[ElasticSearchViewEvent]],
      index: ElasticSearchViewCache,
      views: ElasticSearchViews,
      startCoordinator: StartCoordinator,
      stopCoordinator: StopCoordinator
  )(implicit as: ActorSystem[Nothing], sc: Scheduler): Task[StreamSupervisor] =
    StreamSupervisor(
      "ElasticSearchViewsIndex",
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
            case res @ ResourceF(_, _, _, _, false, _, _, _, _, _, view: IndexingElasticSearchView) =>
              startCoordinator(res.as(view))
            case res @ ResourceF(_, _, _, _, true, _, _, _, _, _, view: IndexingElasticSearchView)  =>
              stopCoordinator(res.as(view))
            case _                                                                                  => Task.unit
          }
      ),
      retryStrategy = RetryStrategy.retryOnNonFatal(config.retry, logger, "elasticsearch views indexing")
    )
}
