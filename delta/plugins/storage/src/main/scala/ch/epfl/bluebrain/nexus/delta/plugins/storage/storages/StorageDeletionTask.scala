package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages

import cats.effect.IO
import cats.implicits.catsSyntaxFlatMapOps
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageDeletionTask.{init, logger}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import fs2.Stream
import org.typelevel.log4cats

/**
  * Creates a project deletion step that deletes the directory associated to a local storage and all the physical files
  * written under it. No deletion occurs for other types of storage.
  */
final class StorageDeletionTask(currentStorages: ProjectRef => Stream[IO, StorageValue]) extends ProjectDeletionTask {

  override def apply(project: ProjectRef)(implicit subject: Subject): IO[ProjectDeletionReport.Stage] =
    logger.info(s"Starting deletion of local files for $project") >>
      run(project)

  private def run(project: ProjectRef) =
    currentStorages(project)
      .evalScan(init) {
        case (acc, disk: DiskStorageValue)         =>
          deleteRecursively(project, disk).map(acc ++ _)
        case (acc, remote: RemoteDiskStorageValue) =>
          val message =
            s"Deletion of files for remote storages is yet to be implemented. Files in folder '${remote.folder}' will remain."
          logger.warn(message).as(acc ++ message)
        case (acc, s3: S3StorageValue)             =>
          val message =
            s"Deletion of files for S3 storages is yet to be implemented. Files in bucket '${s3.bucket}' will remain."
          logger.warn(message).as(acc ++ message)
      }
      .compile
      .lastOrError

  private def deleteRecursively(project: ProjectRef, disk: DiskStorageValue) = {
    val directory = disk.rootDirectory(project)
    if (directory.exists)
      IO.delay {
        if (!directory.deleteRecursively()) {
          s"Local directory '${directory.path}' could not be deleted."
        } else
          s"Local directory '${directory.path}' have been deleted."
      }
    else
      IO.pure(s"Local directory '${directory.path}' does no exist.")
  }

}

object StorageDeletionTask {

  private val logger: log4cats.Logger[IO] = Logger.cats[StorageDeletionTask]

  private val init = ProjectDeletionReport.Stage.empty("storage")

  def apply(storages: Storages) =
    new StorageDeletionTask(project =>
      storages
        .currentStorages(project)
        .evalMapFilter {
          _.map(_.value).toIO
        }
    )
}
