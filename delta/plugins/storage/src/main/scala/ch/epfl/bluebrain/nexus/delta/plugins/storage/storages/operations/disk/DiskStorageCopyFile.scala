package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CopyBetween, CopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDetails, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.computeLocation
import fs2.io.file.Path

import java.net.URI
import java.nio.file.Paths

class DiskStorageCopyFile(storage: DiskStorage) extends CopyFile {
  private val logger = Logger[DiskStorageCopyFile]
  override def apply(details: NonEmptyList[CopyFileDetails]): IO[NonEmptyList[FileAttributes]] = {
    details
      .traverse { copyFile =>
        val dest       = copyFile.destinationDesc
        val sourcePath = Paths.get(URI.create(s"file://${copyFile.sourceAttributes.location.path}"))
        for {
          _                            <- logger.info(s"DTBDTB raw source loc is $sourcePath")
          (destPath, destRelativePath) <- computeLocation(storage.project, storage.value, dest.uuid, dest.filename)
        } yield sourcePath -> FileAttributes(
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
      .flatMap { destinationAttributesBySourcePath =>
        val paths = destinationAttributesBySourcePath.map { case (sourcePath, destAttr) =>
          val source = Path.fromNioPath(sourcePath)
          val dest   = Path.fromNioPath(Paths.get(URI.create(s"file://${destAttr.location.path}")))
          CopyBetween(source, dest)
        }
        logger.info(s"DTBDTB about to do file copy for paths $paths") >>
          CopyFiles
            .copyAll(paths)
            .onError { e =>
              logger.error(s"DTBDTB disk file copy failed with $e")

            }
            .as {
              destinationAttributesBySourcePath.map { case (_, destAttr) => destAttr }
            } <* logger.info("DTBDTB completed file copy")
      }
  }
}
