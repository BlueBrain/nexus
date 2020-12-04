package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import monix.bio.IO

sealed private trait StorageAccess {

  type Storage <: StorageValue

  /**
    * Checks whether the system has access to the passed ''storage''
    *
    * @return a [[Unit]] if access has been verified successfully or signals an error [[StorageNotAccessible]]
    *         with the details about why the storage is not accessible
    */
  def apply(storage: Storage): IO[StorageNotAccessible, Unit]
}

object StorageAccess {

  final private[storage] def apply(storage: StorageValue): IO[StorageNotAccessible, Unit] =
    storage match {
      case storage: DiskStorageValue       => DiskStorageAccess(storage)
      case storage: S3StorageValue         => S3StorageAccess(storage)
      case storage: RemoteDiskStorageValue => RemoteDiskStorageAccess(storage)
    }

  private object DiskStorageAccess       extends StorageAccess {
    override type Storage = DiskStorageValue
    override def apply(storage: Storage): IO[StorageNotAccessible, Unit] = ???
  }
  private object S3StorageAccess         extends StorageAccess {
    override type Storage = S3StorageValue
    override def apply(storage: Storage): IO[StorageNotAccessible, Unit] = ???
  }
  private object RemoteDiskStorageAccess extends StorageAccess {
    override type Storage = RemoteDiskStorageValue
    override def apply(storage: Storage): IO[StorageNotAccessible, Unit] = ???
  }
}
