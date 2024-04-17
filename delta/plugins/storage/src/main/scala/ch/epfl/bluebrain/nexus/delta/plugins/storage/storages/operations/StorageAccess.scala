package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue

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
