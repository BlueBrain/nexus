package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.DifferentBlazegraphViewType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.MigrationState
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, IndexingStreamCoordinator}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

object BlazegraphIndexingCoordinator {

  type BlazegraphIndexingCoordinator = IndexingStreamCoordinator[IndexingBlazegraphView]
  type BlazegraphIndexingController  = IndexingStreamController[IndexingBlazegraphView]

  implicit private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  private def fetchView(views: BlazegraphViews, config: BlazegraphViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .map { res =>
        Some(
          ViewIndex(
            res.value.project,
            res.id,
            res.value.uuid,
            BlazegraphViews.projectionId(res),
            BlazegraphViews.namespace(res, config.indexing),
            res.rev,
            res.deprecated,
            res.value.resourceTag,
            res.updatedAt,
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
      indexingController: BlazegraphIndexingController,
      indexingStream: BlazegraphIndexingStream,
      indexingCleanup: BlazegraphIndexingCleanup,
      config: BlazegraphViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[BlazegraphIndexingCoordinator] =
    Task
      .delay {
        val retryStrategy =
          RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "blazegraph indexing")

        IndexingStreamCoordinator(
          indexingController,
          fetchView(views, config),
          indexingStream,
          indexingCleanup,
          retryStrategy
        )
      }
      .tapEval { coordinator =>
        IO.unless(MigrationState.isIndexingDisabled)(
          BlazegraphViewsIndexing.startIndexingStreams(config.indexing.retry, views, coordinator)
        )
      }
}
