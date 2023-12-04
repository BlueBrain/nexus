package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{CopyBetween, CopyFiles}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDetails, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.computeLocation
import fs2.io.file.Path

import java.net.URI
import java.nio.file.Paths

class DiskStorageCopyFile(storage: DiskStorage) extends CopyFile {
  override def apply(details: NonEmptyList[CopyFileDetails]): IO[NonEmptyList[FileAttributes]] = {
    details
      .traverse { copyFile =>
        val dest       = copyFile.destinationDesc
        val sourcePath = Paths.get(URI.create(s"file://${copyFile.sourceAttributes.location.path}"))
        for {
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
          CopyBetween(Path.fromNioPath(sourcePath), Path(destAttr.location.toString()))
        }
        CopyFiles.copyAll(paths).as {
          destinationAttributesBySourcePath.map { case (_, destAttr) => destAttr }
        }
      }
  }
}
