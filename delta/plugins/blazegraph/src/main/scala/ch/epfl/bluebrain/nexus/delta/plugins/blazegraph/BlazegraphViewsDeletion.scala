package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.{BlazegraphViewsAggregate, BlazegraphViewsCache}
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.BlazegraphIndexingCoordinator.BlazegraphIndexingCoordinator
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{BlazegraphViewEvent, BlazegraphViewType}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.Task

final class BlazegraphViewsDeletion(
    cache: BlazegraphViewsCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[BlazegraphViewEvent],
    dbCleanup: DatabaseCleanup,
    coordinator: BlazegraphIndexingCoordinator
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, BlazegraphViews.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    cache
      .values(projectRef)
      .flatMap { viewsList =>
        Task.traverse(viewsList) { view =>
          view.value.tpe match {
            case BlazegraphViewType.IndexingBlazegraphView  =>
              coordinator.cleanUpAndStop(view.id, projectRef)
            case BlazegraphViewType.AggregateBlazegraphView =>
              Task.unit
          }
        }
      }
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object BlazegraphViewsDeletion {
  final def apply(
      cache: BlazegraphViewsCache,
      agg: BlazegraphViewsAggregate,
      views: BlazegraphViews,
      dbCleanup: DatabaseCleanup,
      coordinator: BlazegraphIndexingCoordinator
  ): BlazegraphViewsDeletion =
    new BlazegraphViewsDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        views.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup,
      coordinator
    )
}
