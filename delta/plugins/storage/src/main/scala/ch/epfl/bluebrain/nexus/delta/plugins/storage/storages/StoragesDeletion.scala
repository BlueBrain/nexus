package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages.{StoragesAggregate, StoragesCache}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesDeletion.logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import com.typesafe.scalalogging.Logger
import monix.bio.Task

import java.io.File
import scala.reflect.io.Directory

final class StoragesDeletion(
    cache: StoragesCache,
    stopActor: StopActor,
    currentEvents: CurrentEvents[StorageEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion(stopActor, currentEvents, dbCleanup, Storages.moduleType)(_.id) {

  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    cache
      .values(projectRef)
      .flatMap { storagesList =>
        Task.traverse(storagesList) { storage =>
          storage.value.storageValue match {
            case d: DiskStorageValue =>
              val directory = new Directory(new File(d.volume.value.toFile, projectRef.toString))

              Task.when(directory.exists) {
                Task.delay {
                  if (!directory.deleteRecursively()) {
                    logger.warn(s"Directory ${directory.path} could not be deleted")
                  }
                }
              }
            case _                   =>
              Task.delay(
                logger.warn(
                  s"So far we only delete files from ''DiskStorage''. To be implemented for S3Storages and RemoteDiskStorages"
                )
              )
          }
        }

      }
      .as(ResourcesDataDeleted)

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    cache.remove(projectRef).as(CachesDeleted)

}

object StoragesDeletion {

  private val logger: Logger = Logger[StoragesDeletion.type]

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
