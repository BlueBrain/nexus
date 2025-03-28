package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.DiskUploadingFile
import ch.epfl.bluebrain.nexus.delta.kernel.AkkaSource

trait DiskFileOperations {
  def fetch(path: Uri.Path): IO[AkkaSource]

  def save(uploading: DiskUploadingFile): IO[FileStorageMetadata]
}

object DiskFileOperations {
  def mk(implicit as: ActorSystem, uuidf: UUIDF): DiskFileOperations = new DiskFileOperations {

    private val saveFile = new DiskStorageSaveFile()

    override def fetch(path: Uri.Path): IO[AkkaSource] = absoluteDiskPath(path).redeemWith(
      e => IO.raiseError(UnexpectedLocationFormat(s"file://$path", e.getMessage)),
      path =>
        IO.blocking(path.toFile.exists()).flatMap { exists =>
          if (exists) IO.blocking(FileIO.fromPath(path))
          else IO.raiseError(FetchFileRejection.FileNotFound(path.toString))
        }
    )

    override def save(uploading: DiskUploadingFile): IO[FileStorageMetadata] = saveFile.apply(uploading)
  }
}
