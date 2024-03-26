package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient

trait StorageAccess {

  /**
    * Checks whether the system has access to the passed ''storage''
    *
    * @return
    *   a [[Unit]] if access has been verified successfully or signals an error [[StorageNotAccessible]] with the
    *   details about why the storage is not accessible
    */
  def apply(storage: StorageValue): IO[Unit]
}

object StorageAccess {
  def mk(
      remoteStorageClient: RemoteDiskStorageClient,
      s3Client: S3StorageClient
  ): StorageAccess = {
    case s: DiskStorageValue       => DiskStorageAccess(s)
    case s: S3StorageValue         => new S3StorageAccess(s3Client).apply(s.bucket)
    case s: RemoteDiskStorageValue => new RemoteDiskStorageAccess(remoteStorageClient).apply(s)
  }
}
