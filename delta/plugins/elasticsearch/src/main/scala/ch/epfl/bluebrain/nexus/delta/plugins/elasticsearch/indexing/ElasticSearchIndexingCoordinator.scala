package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.DifferentElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, IndexingStreamCoordinator}
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object ElasticSearchIndexingCoordinator {

  type ElasticSearchIndexingCoordinator = IndexingStreamCoordinator[IndexingElasticSearchView]
  type ElasticSearchIndexingController  = IndexingStreamController[IndexingElasticSearchView]

  implicit private val logger: Logger = Logger[ElasticSearchIndexingCoordinator.type]

  private def fetchView(views: ElasticSearchViews, config: ElasticSearchViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .map { res =>
        Some(
          IndexingElasticSearchView.resourceToViewIndex(res, config)
        )
      }
      .onErrorHandle {
        case _: DifferentElasticSearchViewType =>
          logger.debug(s"Filtering out aggregate view from ")
          None
        case r                                 =>
          logger.error(
            s"While attempting to start indexing view $id in project $project, the rejection $r was encountered"
          )
          None
      }

  /**
    * Create a coordinator for indexing documents into ElasticSearch indices triggered and customized by the
    * ElasticSearchViews.
    */
  def apply(
      views: ElasticSearchViews,
      indexingController: ElasticSearchIndexingController,
      indexingStream: ElasticSearchIndexingStream,
      indexingCleanup: ElasticSearchIndexingCleanup,
      config: ElasticSearchViewsConfig,
      beforeRunning: Task[Unit] = Task.unit
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[ElasticSearchIndexingCoordinator] = Task
    .delay {
      val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "elasticsearch indexing")

      IndexingStreamCoordinator[IndexingElasticSearchView](
        indexingController,
        fetchView(views, config),
        _ => config.idleTimeout,
        indexingStream,
        indexingCleanup,
        retryStrategy
      )
    }
    .tapEval(ElasticSearchViewsIndexing.startIndexingStreams(config.indexing.retry, views, _, beforeRunning))

}
