package ch.epfl.bluebrain.nexus.delta.plugins.storage.files

import akka.persistence.query.NoOffset
import ch.epfl.bluebrain.nexus.delta.kernel.Mapper
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.FilesAggregate
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.FilesDeletion.logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileEvent._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.Storages
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.sdk.ResourcesDeletion.{CurrentEvents, ProjectScopedResourcesDeletion, StopActor}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourcesDeletionProgress.{CachesDeleted, ResourcesDataDeleted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.DatabaseCleanup
import com.typesafe.scalalogging.Logger
import monix.bio.Task

import java.io.File
import scala.reflect.io.Directory

final class FilesDeletion(
    storages: Storages,
    stopActor: StopActor,
    currentEvents: CurrentEvents[FileEvent],
    dbCleanup: DatabaseCleanup
) extends ProjectScopedResourcesDeletion[FileEvent](stopActor, currentEvents, dbCleanup, Files.moduleType)(_.id) {

  implicit private val mapper: Mapper[StorageFetchRejection, Throwable] =
    rej => new IllegalArgumentException(rej.reason)

  // TODO: So far we only delete files from ''DiskStorage''. To be implemented for S3Storages and RemoteDiskStorages
  override def freeResources(projectRef: ProjectRef): Task[ResourcesDataDeleted] =
    currentEvents(projectRef, NoOffset).flatMap { stream =>
      stream
        .collect {
          case Envelope(file: FileCreated, _, _, _, _, _) if file.storageType == StorageType.DiskStorage => file.storage
          case Envelope(file: FileUpdated, _, _, _, _, _) if file.storageType == StorageType.DiskStorage => file.storage
        }
        .evalMap { ref =>
          storages.fetch(ref, projectRef).map(_.value)
        }
        .collect { case Storage.DiskStorage(_, _, value, _, _) => value.volume }
        .changes
        .evalMap { volume =>
          val directory = new Directory(new File(volume.value.toFile, projectRef.toString))
          if (directory.exists) directory.deleteRecursively() else true

          Task.when(directory.exists) {
            Task.delay {
              if (!directory.deleteRecursively()) {
                logger.warn(s"Directory ${directory.path} could not be deleted")
              }
            }
          }
        }
        .compile
        .drain
        .as(ResourcesDataDeleted)
    }

  override def deleteCaches(projectRef: ProjectRef): Task[CachesDeleted] =
    Task.pure(CachesDeleted)

}

object FilesDeletion {

  private val logger: Logger = Logger[FilesDeletion.type]

  final def apply(
      agg: FilesAggregate,
      storages: Storages,
      files: Files,
      dbCleanup: DatabaseCleanup
  ): FilesDeletion =
    new FilesDeletion(
      storages,
      agg.stop,
      (project, offset) =>
        files.currentEvents(project, offset).mapError(rej => new IllegalArgumentException(rej.reason)),
      dbCleanup
    )
}
