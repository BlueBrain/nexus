package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.DifferentElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewSearchParams
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamAwake.CurrentViews
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamAwake, IndexingStreamController, IndexingStreamCoordinator}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
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
          ViewIndex(
            res.value.project,
            res.id,
            res.value.uuid,
            ElasticSearchViews.projectionId(res),
            ElasticSearchViews.index(res, config.indexing),
            res.rev,
            res.deprecated,
            res.value.resourceTag,
            res.updatedAt,
            res.value
          )
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
    * Create a coordinator for indexing documents into ElasticSearch indices triggered and customized by the ElasticSearchViews.
    */
  def apply(
      views: ElasticSearchViews,
      indexingController: ElasticSearchIndexingController,
      eventLog: EventLog[Envelope[ProjectScopedEvent]],
      indexingStream: ElasticSearchIndexingStream,
      indexingCleanup: ElasticSearchIndexingCleanup,
      config: ElasticSearchViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[ElasticSearchIndexingCoordinator] = {
    val currentViews: CurrentViews = project =>
      views
        .list(
          Pagination.OnePage,
          ElasticSearchViewSearchParams(project = Some(project), deprecated = Some(false), filter = _ => true),
          ResourceF.defaultSort
        )
        .map(_.results.map(v => Revision(v.source.id, v.source.rev)))
    Task
      .delay {
        val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "elasticsearch indexing")

        IndexingStreamCoordinator(
          indexingController,
          fetchView(views, config),
          config.idleTimeout,
          indexingStream,
          indexingCleanup,
          retryStrategy
        )
      }
      .tapEval { coordinator =>
        IO.unless(MigrationState.isIndexingDisabled)(
          ElasticSearchViewsIndexing.startIndexingStreams(config.indexing.retry, views, coordinator) >>
            IndexingStreamAwake.start(eventLog, coordinator, currentViews, config.idleTimeout, config.indexing.retry)
        )
      }
  }

}
