package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FileResource, Files}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef

trait BatchFiles {
  def copyFiles(
      source: CopyFileSource,
      dest: CopyFileDestination
  )(implicit c: Caller): IO[NonEmptyList[FileResource]]
}

object BatchFiles {
  def mk(files: Files, fetchContext: FetchContext[FileRejection], batchCopy: BatchCopy): BatchFiles = new BatchFiles {

    private val logger = Logger[BatchFiles]

    implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

    override def copyFiles(source: CopyFileSource, dest: CopyFileDestination)(implicit
        c: Caller
    ): IO[NonEmptyList[FileResource]] = {
      for {
        pc                            <- fetchContext.onCreate(dest.project)
        (destStorageRef, destStorage) <- files.fetchAndValidateActiveStorage(dest.storage, dest.project, pc)
        destFilesAttributes           <- batchCopy.copyFiles(source, destStorage)
        fileResources                 <- evalCreateCommands(pc, dest, destStorageRef, destStorage.tpe, destFilesAttributes)
      } yield fileResources
    }.span("copyFiles")

    private def evalCreateCommands(
        pc: ProjectContext,
        dest: CopyFileDestination,
        destStorageRef: ResourceRef.Revision,
        destStorageTpe: StorageType,
        destFilesAttributes: NonEmptyList[FileAttributes]
    )(implicit c: Caller): IO[NonEmptyList[FileResource]] =
      destFilesAttributes.traverse { destFileAttributes =>
        for {
          iri      <- files.generateId(pc)
          command   =
            CreateFile(iri, dest.project, destStorageRef, destStorageTpe, destFileAttributes, c.subject, dest.tag)
          resource <- evalCreateCommand(files, command)
        } yield resource
      }

    private def evalCreateCommand(files: Files, command: CreateFile) =
      files.eval(command).onError { e =>
        logger.error(e)(s"Failed storing file copy event, file must be manually deleted: $command")
      }
  }

}
