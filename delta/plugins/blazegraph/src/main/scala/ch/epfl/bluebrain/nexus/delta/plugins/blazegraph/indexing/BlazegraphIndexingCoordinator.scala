package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.BlazegraphClient
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingEventLog.BlazegraphIndexingEventLog
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.DifferentBlazegraphViewType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
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

object BlazegraphIndexingCoordinator {

  implicit private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  type BlazegraphIndexingCoordinator = IndexingStreamCoordinator[IndexingBlazegraphView]

  private def fetchView(views: BlazegraphViews, config: BlazegraphViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .map { res =>
        Some(
          ViewIndex(
            res.value.project,
            res.id,
            BlazegraphViews.projectionId(res),
            BlazegraphViews.index(res, config.indexing),
            res.rev,
            res.deprecated,
            res.value
          )
        )
      }
      .onErrorHandle {
        case _: DifferentBlazegraphViewType =>
          logger.debug(s"Filtering out aggregate views")
          None
        case r                              =>
          logger.error(
            s"While attempting to start indexing view $id in project $project, the rejection $r was encountered"
          )
          None
      }

  /**
    * Create a coordinator for indexing triples into Blazegraph namespaces triggered and customized by the BlazegraphViews.
    */
  def apply(
      views: BlazegraphViews,
      indexingLog: BlazegraphIndexingEventLog,
      client: BlazegraphClient,
      projection: Projection[Unit],
      cache: ProgressesCache,
      config: BlazegraphViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler,
      base: BaseUri,
      resolution: RemoteContextResolution
  ): Task[BlazegraphIndexingCoordinator] =
    Task
      .delay {
        val indexingRetryStrategy =
          RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "blazegraph indexing")

        new IndexingStreamCoordinator[IndexingBlazegraphView](
          BlazegraphViews.moduleType,
          fetchView(views, config),
          new BlazegraphStreamBuilder(client, cache, config, indexingLog, projection),
          indexingRetryStrategy
        )
      }
      .tapEval { coordinator =>
        IO.unless(MigrationState.isIndexingDisabled)(
          BlazegraphViewsIndexing.startIndexingStreams(config.indexing, views, coordinator)
        )
      }
}
