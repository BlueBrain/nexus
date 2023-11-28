package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

trait CopyFile {
  def apply(source: FileAttributes, dest: FileDescription): IO[FileAttributes]
}

object CopyFile {

  def apply(storage: Storage, client: RemoteDiskStorageClient): CopyFile =
    storage match {
      case storage: Storage.DiskStorage       => storage.copyFile
      case storage: Storage.S3Storage         => unsupported(storage.tpe)
      case storage: Storage.RemoteDiskStorage => storage.copyFile(client)
    }

  private def unsupported(storageType: StorageType): CopyFile =
    (_, _) => IO.raiseError(CopyFileRejection.UnsupportedOperation(storageType))

}
