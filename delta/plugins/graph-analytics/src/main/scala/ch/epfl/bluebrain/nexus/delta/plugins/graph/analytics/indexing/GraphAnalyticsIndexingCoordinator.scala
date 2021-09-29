package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.kernel.{RetryStrategy, RetryStrategyConfig}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.GraphAnalytics
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.config.GraphAnalyticsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.Projects
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

  implicit private val logger: Logger = Logger[GraphAnalyticsIndexingCoordinator.type]

  private def graphAnalyticsView(projects: Projects)(implicit config: ExternalIndexingConfig) =
    (id: Iri, project: ProjectRef) =>
      {
        def logError[A](err: A) = {
          logger.error(
            s"While attempting to start indexing view $id in project $project, the rejection $err was encountered"
          )
          err
        }

        for {
          view <- GraphAnalyticsView.default.mapError(logError)
          res  <- projects.fetch(project).mapError(logError)
        } yield Some(
          ViewIndex(
            project,
            id,
            res.value.uuid,
            GraphAnalytics.projectionId(project),
            GraphAnalytics.idx(project).value,
            1,
            deprecated = false,
            None,
            res.updatedAt,
            view
          )
        )
      }.onErrorHandle(_ => None)

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
    Task
      .delay {
        val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "graph analytics indexing")

        IndexingStreamCoordinator[GraphAnalyticsView](
          indexingController,
          graphAnalyticsView(projects),
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
    def onEvent(event: ProjectEvent) =
      coordinator.run(GraphAnalytics.typeStats, event.project, 1)

    val name = "GraphAnalyticsCoordinatorScan"
    DaemonStreamCoordinator.run(
      name,
      stream = projects.events(Offset.noOffset).evalMap { e => onEvent(e.event) },
      retryStrategy = RetryStrategy.retryOnNonFatal(retry, logger, name)
    )
  }

}
