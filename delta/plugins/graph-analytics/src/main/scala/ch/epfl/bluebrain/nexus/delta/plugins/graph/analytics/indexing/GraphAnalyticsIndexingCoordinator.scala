package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalytics
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectEvent.ProjectCreated
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectEvent, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, IndexingStreamCoordinator}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.stream.DaemonStreamCoordinator
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object GraphAnalyticsIndexingCoordinator {

  type GraphAnalyticsIndexingCoordinator = IndexingStreamCoordinator[GraphAnalyticsView]
  type GraphAnalyticsIndexingController  = IndexingStreamController[GraphAnalyticsView]

  private val logger: Logger = Logger[GraphAnalyticsIndexingCoordinator.type]

  private def graphAnalyticsView(implicit config: ExternalIndexingConfig) =
    (id: Iri, project: ProjectRef) =>
      GraphAnalyticsView.default.map { g =>
        Some(
          ViewIndex(
            project,
            id,
            GraphAnalytics.projectionId(project),
            GraphAnalytics.idx(project).value,
            1,
            deprecated = false,
            None,
            g
          )
        )
      }

  /**
    * Create a coordinator for indexing documents into ElasticSearch indices triggered and customized by the
    * ElasticSearchViews.
    */
  def apply(
      projects: Projects,
      indexingController: GraphAnalyticsIndexingController,
      indexingStream: GraphAnalyticsIndexingStream,
      indexingCleanup: GraphAnalyticsIndexingCleanup,
      config: GraphAnalyticsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[GraphAnalyticsIndexingCoordinator] = {
    implicit val idxConfig: ExternalIndexingConfig = config.indexing
    Task.when(sys.env.getOrElse("GRAPH_ANALYTICS_CLEANUP", "false").toBoolean) {
      cleanUp(projects, indexingCleanup)
    } >>
      Task
        .delay {
          val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "graph analytics indexing")
          IndexingStreamCoordinator[GraphAnalyticsView](
            indexingController,
            graphAnalyticsView,
            _ => config.idleTimeout,
            indexingStream,
            indexingCleanup,
            retryStrategy
          )
        }
        .tapEval(startIndexingStreams(config.indexing.retry, projects, _))
  }

  private def startIndexingStreams(
      retry: RetryStrategyConfig,
      projects: Projects,
      coordinator: GraphAnalyticsIndexingCoordinator
  )(implicit uuidF: UUIDF, as: ActorSystem[Nothing], sc: Scheduler): Task[Unit] = {
    def onEvent(event: ProjectEvent): Task[Unit] = event match {
      case _: ProjectCreated => coordinator.run(GraphAnalytics.typeStats, event.project, 1)
      case _                 => Task.unit
    }

    val name = "GraphAnalyticsCoordinatorScan"
    DaemonStreamCoordinator.run(
      name,
      stream = projects.events(Offset.noOffset).evalMap { e => onEvent(e.event) },
      retryStrategy = RetryStrategy.retryOnNonFatal(retry, logger, name)
    )
  }

  private[indexing] def cleanUp(projects: Projects, indexingCleanup: GraphAnalyticsIndexingCleanup)(implicit
      config: ExternalIndexingConfig
  ) = {
    def getView = graphAnalyticsView
    Task.delay(logger.warn("Cleaning up graph-analytics indices and progress to start from the beginning")) >>
      projects
        .currentEvents(NoOffset)
        .evalTap {
          _.event match {
            case c: ProjectCreated =>
              getView(GraphAnalytics.typeStats, c.project).flatMap {
                _.fold(Task.unit)(indexingCleanup(_))
              }
            case _                 => Task.unit
          }
        }
        .compile
        .toList
        .void >>
      Task.delay(logger.info("Cleaning up completed, indexing will now start again."))
  }

}
