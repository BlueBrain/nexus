package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.ElasticSearchViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.indexing.ElasticSearchIndexingEventLog.ElasticSearchIndexingEventLog
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.IndexingElasticSearchView
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchViewRejection.DifferentElasticSearchViewType
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.ProgressesStatistics.ProgressesCache
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.IndexingStreamCoordinator
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Projection
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object ElasticSearchIndexingCoordinator {

  type ElasticSearchIndexingCoordinator = IndexingStreamCoordinator[IndexingElasticSearchView]

  implicit private val logger: Logger = Logger[ElasticSearchIndexingCoordinator.type]

  private def fetchView(views: ElasticSearchViews, config: ElasticSearchViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .map { res =>
        Some(
          ViewIndex(
            res.value.project,
            res.id,
            ElasticSearchViews.projectionId(res),
            ElasticSearchViews.index(res, config.indexing),
            res.rev,
            res.deprecated,
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
      indexingLog: ElasticSearchIndexingEventLog,
      client: ElasticSearchClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: ElasticSearchViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      cr: RemoteContextResolution,
      base: BaseUri
  ): Task[ElasticSearchIndexingCoordinator] = Task
    .delay {
      val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "elasticsearch indexing")

      new IndexingStreamCoordinator[IndexingElasticSearchView](
        ElasticSearchViews.moduleType,
        fetchView(views, config),
        new ElasticSearchStreamBuilder(client, cache, config, indexingLog, projection),
        retryStrategy
      )
    }
    .tapEval { coordinator =>
      IO.unless(MigrationState.isIndexingDisabled)(
        ElasticSearchViewsIndexing.startIndexingStreams(config.indexing, views, coordinator)
      )
    }

}
