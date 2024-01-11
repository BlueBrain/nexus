package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CopyBetween, TransactionalFileCopier}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.computeLocation
import fs2.io.file.Path

import java.nio.file

trait DiskStorageCopyFiles {
  def copyFiles(destStorage: DiskStorage, details: NonEmptyList[DiskCopyDetails]): IO[NonEmptyList[FileAttributes]]
}

object DiskStorageCopyFiles {
  def mk(copier: TransactionalFileCopier): DiskStorageCopyFiles = new DiskStorageCopyFiles {

    def copyFiles(destStorage: DiskStorage, details: NonEmptyList[DiskCopyDetails]): IO[NonEmptyList[FileAttributes]] =
      details
        .traverse(mkCopyDetailsAndDestAttributes(destStorage, _))
        .flatMap { copyDetailsAndDestAttributes =>
          val copyDetails = copyDetailsAndDestAttributes.map(_._1)
          val destDetails = copyDetailsAndDestAttributes.map { case (_, attributes) =>
            attributes
          }
          copier.copyAll(copyDetails).as(destDetails)
        }

    private def mkCopyDetailsAndDestAttributes(destStorage: DiskStorage, copyFile: DiskCopyDetails) =
      for {
        sourcePath                   <- absoluteDiskPathFromAttributes(copyFile.sourceAttributes)
        (destPath, destRelativePath) <- computeDestLocation(destStorage, copyFile)
        destAttr                      = mkDestAttributes(copyFile, destPath, destRelativePath)
        copyDetails                  <- absoluteDiskPathFromAttributes(destAttr).map { dest =>
                                          CopyBetween(Path.fromNioPath(sourcePath), Path.fromNioPath(dest))
                                        }
      } yield (copyDetails, destAttr)

    private def computeDestLocation(destStorage: DiskStorage, cd: DiskCopyDetails) =
      computeLocation(destStorage.project, destStorage.value, cd.destinationDesc.uuid, cd.destinationDesc.filename)

    private def mkDestAttributes(cd: DiskCopyDetails, destPath: file.Path, destRelativePath: file.Path) =
      FileAttributes(
        uuid = cd.destinationDesc.uuid,
        location = Uri(destPath.toUri.toString),
        path = Uri.Path(destRelativePath.toString),
        filename = cd.destinationDesc.filename,
        mediaType = cd.sourceAttributes.mediaType,
        bytes = cd.sourceAttributes.bytes,
        digest = cd.sourceAttributes.digest,
        origin = cd.sourceAttributes.origin
      )
  }
}
