package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.data.NonEmptyList
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.TransactionalFileCopier
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{CopyFileDetails, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.CopyFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

trait CopyFiles {
  def apply(copyDetails: NonEmptyList[CopyFileDetails]): IO[NonEmptyList[FileAttributes]]
}

object CopyFiles {

  def apply(storage: Storage, client: RemoteDiskStorageClient, copier: TransactionalFileCopier): CopyFiles =
    storage match {
      case storage: Storage.DiskStorage       => storage.copyFiles(copier)
      case storage: Storage.S3Storage         => unsupported(storage.tpe)
      case storage: Storage.RemoteDiskStorage => storage.copyFiles(client)
    }

  private def unsupported(storageType: StorageType): CopyFiles =
    _ => IO.raiseError(CopyFileRejection.UnsupportedOperation(storageType))

}
