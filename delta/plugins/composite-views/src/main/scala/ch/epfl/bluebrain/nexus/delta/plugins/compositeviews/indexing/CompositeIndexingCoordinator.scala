package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.config.CompositeViewsConfig
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.Interval
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeView, CompositeViewSearchParams}
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
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionId.ViewProjectionId
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task}
import monix.execution.Scheduler

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
            res.value.uuid,
            ViewProjectionId("none"),
            BlazegraphViews.namespace(res.value.uuid, res.rev, config.blazegraphIndexing),
            res.rev,
            res.deprecated,
            None,
            res.updatedAt,
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
      eventLog: EventLog[Envelope[ProjectScopedEvent]],
      indexingStream: CompositeIndexingStream,
      indexingCleanup: CompositeIndexingCleanup,
      config: CompositeViewsConfig
  )(implicit
      uuidF: UUIDF,
      as: ActorSystem[Nothing],
      scheduler: Scheduler
  ): Task[CompositeIndexingCoordinator] = {
    val currentViews: CurrentViews = project =>
      views
        .list(
          Pagination.OnePage,
          CompositeViewSearchParams(project = Some(project), deprecated = Some(false), filter = _ => true),
          ResourceF.defaultSort
        )
        .map(_.results.map(v => Revision(v.source.id, v.source.rev)))
    Task
      .delay {
        val retryStrategy = RetryStrategy.retryOnNonFatal(config.blazegraphIndexing.retry, logger, "composite indexing")

        IndexingStreamCoordinator(
          indexingController,
          fetchView(views, config),
          (v: CompositeView) =>
            v.rebuildStrategy.fold(config.idleTimeout) { case Interval(duration) => config.idleTimeout plus duration },
          indexingStream,
          indexingCleanup,
          retryStrategy
        )
      }
      .tapEval { coordinator =>
        IO.unless(MigrationState.isIndexingDisabled)(
          CompositeViewsIndexing.startIndexingStreams(config.blazegraphIndexing.retry, views, coordinator) >>
            IndexingStreamAwake
              .start(eventLog, coordinator, currentViews, config.idleTimeout, config.blazegraphIndexing.retry)
        )
      }
  }

}
