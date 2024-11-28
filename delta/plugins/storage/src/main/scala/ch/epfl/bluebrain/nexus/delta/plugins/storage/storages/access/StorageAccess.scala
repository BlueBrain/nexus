package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, S3StorageValue}

trait StorageAccess {

  /**
    * Checks whether the system has access to the passed ''storage''
    *
    * @return
    *   a [[Unit]] if access has been verified successfully or signals an error [[StorageNotAccessible]] with the
    *   details about why the storage is not accessible
    */
  def validateStorageAccess(storage: StorageValue): IO[Unit]
}

object StorageAccess {

  def apply(s3Access: S3StorageAccess): StorageAccess = {
    case d: DiskStorageValue       => DiskStorageAccess.checkVolumeExists(d.volume)
    case s: S3StorageValue         => s3Access.checkBucketExists(s.bucket)
  }

}
