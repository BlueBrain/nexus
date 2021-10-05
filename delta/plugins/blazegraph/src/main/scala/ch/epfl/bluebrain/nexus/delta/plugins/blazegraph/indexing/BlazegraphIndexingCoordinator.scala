package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.IndexingBlazegraphView
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewRejection.DifferentBlazegraphViewType
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphViewsConfig
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, IndexingStreamCoordinator}
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

object BlazegraphIndexingCoordinator {

  type BlazegraphIndexingCoordinator = IndexingStreamCoordinator[IndexingBlazegraphView]
  type BlazegraphIndexingController  = IndexingStreamController[IndexingBlazegraphView]

  implicit private val logger: Logger = Logger[BlazegraphIndexingCoordinator.type]

  private def fetchView(views: BlazegraphViews, config: BlazegraphViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetchIndexingView(id, project)
      .map { res =>
        Some(IndexingBlazegraphView.resourceToViewIndex(res, config))
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
    * Create a coordinator for indexing triples into Blazegraph namespaces triggered and customized by the
    * BlazegraphViews.
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
  ): Task[BlazegraphIndexingCoordinator] = {
    val retryStrategy = RetryStrategy.retryOnNonFatal(config.indexing.retry, logger, "blazegraph indexing")
    Task
      .delay {
        IndexingStreamCoordinator[IndexingBlazegraphView](
          indexingController,
          fetchView(views, config),
          _ => config.idleTimeout,
          indexingStream,
          indexingCleanup,
          retryStrategy
        )
      }
      .tapEval(BlazegraphViewsIndexing.startIndexingStreams(config.indexing.retry, views, _))
  }
}
