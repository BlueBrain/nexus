package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.indexing.{IndexingStreamController, IndexingStreamCoordinator}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewIndex
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import com.typesafe.scalalogging.Logger
import monix.bio.Task
import monix.execution.Scheduler

import scala.concurrent.duration._

object CompositeIndexingCoordinator {

  type CompositeIndexingCoordinator = IndexingStreamCoordinator[CompositeView]
  type CompositeIndexingController  = IndexingStreamController[CompositeView]

  implicit private val logger: Logger = Logger[CompositeIndexingCoordinator]

  private def fetchView(views: CompositeViews, config: CompositeViewsConfig) = (id: Iri, project: ProjectRef) =>
    views
      .fetch(id, project)
      .map { res =>
        Some(
          ViewIndex(
            res.value.project,
            res.id,
            ViewProjectionId("none"),
            BlazegraphViews.namespace(res.value.uuid, res.rev, config.blazegraphIndexing),
            res.rev,
            res.deprecated,
            None,
            res.value
          )
        )
      }
      .onErrorHandle { r =>
        logger.error(
          s"While attempting to start indexing view $id in project $project, the rejection $r was encountered"
        )
        None
      }

  /**
    * Create a coordinator for indexing projections triggered and customized by the CompositeViews.
    */
  def apply(
      views: CompositeViews,
      indexingController: CompositeIndexingController,
      indexingStream: CompositeIndexingStream,
      indexingCleanup: CompositeIndexingCleanup,
      config: CompositeViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[CompositeIndexingCoordinator] = {

    val retryStrategy = RetryStrategy.retryOnNonFatal(config.blazegraphIndexing.retry, logger, "composite indexing")

    def computeIdleTimeout(v: CompositeView): Duration =
      v.sources.value.collectFirst { case _: RemoteProjectSource => Duration.Inf }.getOrElse {
        v.rebuildStrategy.fold(config.idleTimeout) { case CompositeView.Interval(extraIdleTimeout) =>
          config.idleTimeout.plus(extraIdleTimeout)
        }
      }

    Task
      .delay {
        IndexingStreamCoordinator(
          indexingController,
          fetchView(views, config),
          computeIdleTimeout,
          indexingStream,
          indexingCleanup,
          retryStrategy
        )
      }
      .tapEval(CompositeViewsIndexing.startIndexingStreams(config.blazegraphIndexing.retry, views, _))
  }

}
