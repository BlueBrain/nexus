package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.sdk.FileData
import fs2.Stream
import fs2.io.file.{Files, Path}
import org.http4s.Uri

import java.net.URI
import java.nio.ByteBuffer
import java.nio.file.Paths

trait DiskFileOperations {
  def fetch(path: Uri.Path): FileData

  def save(uploading: DiskUploadingFile): IO[FileStorageMetadata]
}

object DiskFileOperations {

  def mk(implicit uuidf: UUIDF): DiskFileOperations = new DiskFileOperations {

    private val saveFile = new DiskStorageSaveFile()

    private def absoluteDiskPath(relative: Uri.Path) = {
      val location = s"file://$relative"
      Stream
        .eval(
          IO.delay {
            Path.fromNioPath(Paths.get(URI.create(location)))
          }
        )
        .adaptError { case e =>
          UnexpectedLocationFormat(location, e.getMessage)
        }
    }

    override def fetch(path: Uri.Path): FileData =
      for {
        path   <- absoluteDiskPath(path)
        exists <- Stream.eval(Files[IO].exists(path))
        data   <- if (exists) Files[IO].readAll(path).chunks.map { c => ByteBuffer.wrap(c.toArray) }
                  else Stream.raiseError[IO](FetchFileRejection.FileNotFound(path.toString))
      } yield data

    override def save(uploading: DiskUploadingFile): IO[FileStorageMetadata] = saveFile.apply(uploading)
  }
}
