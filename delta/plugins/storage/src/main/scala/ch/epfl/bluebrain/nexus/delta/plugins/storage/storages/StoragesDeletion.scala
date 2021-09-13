package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{StoragesAggregate, StoragesCache}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import monix.bio.Task

final class StoragesDeletion(
    cache: StoragesCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[StorageEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, Storages.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    Task.pure(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object StoragesDeletion {
  final def apply(
      cache: StoragesCache,
      agg: StoragesAggregate,
      storages: Storages,
      dbCleanup: DatabaseCleanup
  ): StoragesDeletion =
    new StoragesDeletion(
      cache,
      agg.stop,
      (project, offset) =>
        storages.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
