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

class DiskCopy(storage: DiskStorage, copier: TransactionalFileCopier) {

  def copyFiles(details: NonEmptyList[DiskCopyDetails]): IO[NonEmptyList[FileAttributes]] =
    details
      .traverse(mkCopyDetailsAndDestAttributes)
      .flatMap { copyDetailsAndDestAttributes =>
        val copyDetails = copyDetailsAndDestAttributes.map(_._1)
        val destAttrs   = copyDetailsAndDestAttributes.map(_._2)
        copier.copyAll(copyDetails).as(destAttrs)
      }

  private def mkCopyDetailsAndDestAttributes(copyFile: DiskCopyDetails) =
    for {
      sourcePath                   <- absoluteDiskPathFromAttributes(copyFile.sourceAttributes)
      (destPath, destRelativePath) <- computeDestLocation(copyFile)
      destAttr                      = mkDestAttributes(copyFile, destPath, destRelativePath)
      copyDetails                  <- absoluteDiskPathFromAttributes(destAttr).map { dest =>
                                        CopyBetween(Path.fromNioPath(sourcePath), Path.fromNioPath(dest))
                                      }
    } yield (copyDetails, destAttr)

  private def computeDestLocation(copyFile: DiskCopyDetails): IO[(file.Path, file.Path)] =
    computeLocation(
      storage.project,
      storage.value,
      copyFile.destinationDesc.uuid,
      copyFile.destinationDesc.filename
    )

  private def mkDestAttributes(copyFile: DiskCopyDetails, destPath: file.Path, destRelativePath: file.Path) = {
    val dest = copyFile.destinationDesc
    FileAttributes(
      uuid = dest.uuid,
      location = Uri(destPath.toUri.toString),
      path = Uri.Path(destRelativePath.toString),
      filename = dest.filename,
      mediaType = copyFile.sourceAttributes.mediaType,
      bytes = copyFile.sourceAttributes.bytes,
      digest = copyFile.sourceAttributes.digest,
      origin = copyFile.sourceAttributes.origin
    )
  }
}
