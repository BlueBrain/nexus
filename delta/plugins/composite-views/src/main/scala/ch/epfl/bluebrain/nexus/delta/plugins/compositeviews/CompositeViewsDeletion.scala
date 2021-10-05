package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews.{CompositeViewsAggregate, CompositeViewsCache}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeIndexingCoordinator.CompositeIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.{CompositeViewEvent, ViewResource}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.{Task, UIO}

final class CompositeViewsDeletion(
    cache: CompositeViewsCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[CompositeViewEvent],
    dbCleanup: DatabaseCleanup,
    coordinator: CompositeIndexingCoordinator
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, CompositeViews.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    getViews(projectRef)
      .flatMap { viewsList =>
        Task.traverse(viewsList) { view =>
          coordinator.cleanUpAndStop(view.id, projectRef)
        }
      }
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    getViews(projectRef)
      .flatMap(views => Task.traverse(views)(view => cache.remove(ViewRef(projectRef, view.id))))
      .as(CachesDeleted)

  private def getViews(projectRef: ProjectRef): UIO[Vector[ViewResource]] =
    cache.values.map(_.filter(_.value.project == projectRef))

}

object CompositeViewsDeletion {
  final def apply(
      cache: CompositeViewsCache,
      agg: CompositeViewsAggregate,
      views: CompositeViews,
      dbCleanup: DatabaseCleanup,
      coordinator: CompositeIndexingCoordinator
  ): CompositeViewsDeletion =
    new CompositeViewsDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      coordinator
    )
}
