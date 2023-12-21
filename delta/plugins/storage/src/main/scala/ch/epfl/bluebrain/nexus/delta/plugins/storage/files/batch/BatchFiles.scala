package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.batch

import cats.data.NonEmptyList
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files.entityType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileCommand._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.CopyRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.CopyFileSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.{FetchFileStorage, FileResource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
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
  def mk(
      fetchFileStorage: FetchFileStorage,
      fetchContext: FetchContext[FileRejection],
      evalFileCommand: CreateFile => IO[FileResource],
      batchCopy: BatchCopy
  )(implicit uuidF: UUIDF): BatchFiles = new BatchFiles {

    private val logger = Logger[BatchFiles]

    implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent(entityType.value)

    override def copyFiles(source: CopyFileSource, dest: CopyFileDestination)(implicit
        c: Caller
    ): IO[NonEmptyList[FileResource]] = {
      for {
        pc                            <- fetchContext.onCreate(dest.project)
        (destStorageRef, destStorage) <- fetchFileStorage.fetchAndValidateActiveStorage(dest.storage, dest.project, pc)
        destFilesAttributes           <- batchCopy.copyFiles(source, destStorage).adaptError { case e: CopyFileRejection =>
                                           CopyRejection(source.project, dest.project, destStorage.id, e)
                                         }
        sourceIris                    <- source.files.traverse(_.toResourceRef(fetchContext.onRead))
        destAttrAndSourceIris          = destFilesAttributes.zip(sourceIris)
        fileResources                 <- createFileResources(pc, dest, destStorageRef, destStorage.tpe, destAttrAndSourceIris)
      } yield fileResources
    }.span("copyFiles")

    private def createFileResources(
        pc: ProjectContext,
        dest: CopyFileDestination,
        destStorageRef: ResourceRef.Revision,
        destStorageTpe: StorageType,
        destAttrAndSourceResourceRefs: NonEmptyList[(FileAttributes, ResourceRef)]
    )(implicit c: Caller): IO[NonEmptyList[FileResource]] =
      destAttrAndSourceResourceRefs.traverse { case (destFileAttributes, source) =>
        for {
          iri      <- generateId(pc)
          command   =
            CreateFile(
              iri,
              dest.project,
              destStorageRef,
              destStorageTpe,
              destFileAttributes,
              c.subject,
              dest.tag,
              Some(source)
            )
          resource <- evalCreateCommand(command)
        } yield resource
      }

    private def generateId(pc: ProjectContext): IO[Iri] =
      uuidF().map(uuid => pc.base.iri / uuid.toString)

    private def evalCreateCommand(command: CreateFile) =
      evalFileCommand(command).onError { e =>
        logger.error(e)(s"Failed storing file copy event, file must be manually deleted: $command")
      }
  }

}
