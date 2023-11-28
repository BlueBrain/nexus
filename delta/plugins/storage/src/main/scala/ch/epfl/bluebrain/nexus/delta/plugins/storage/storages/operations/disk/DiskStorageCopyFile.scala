package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.CopyFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageSaveFile.initLocation

import java.net.URI
import java.nio.file.{Paths, StandardCopyOption}
import scala.annotation.nowarn

class DiskStorageCopyFile(storage: DiskStorage) extends CopyFile {
  @nowarn
  override def apply(source: FileAttributes, dest: FileDescription): IO[FileAttributes] = {
    val sourcePath = Paths.get(URI.create(s"file://${source.location.path}"))
    for {
      (destPath, destRelativePath) <- initLocation(storage.project, storage.value, dest.uuid, dest.filename)
      _                            <- fs2.io.file.copy[IO](sourcePath, destPath, Seq(StandardCopyOption.COPY_ATTRIBUTES))
    } yield FileAttributes(
      uuid = dest.uuid,
      location = Uri(destPath.toUri.toString),
      path = Uri.Path(destRelativePath.toString),
      filename = dest.filename,
      mediaType = source.mediaType,
      bytes = source.bytes,
      digest = source.digest,
      origin = source.origin
    )
  }
}
