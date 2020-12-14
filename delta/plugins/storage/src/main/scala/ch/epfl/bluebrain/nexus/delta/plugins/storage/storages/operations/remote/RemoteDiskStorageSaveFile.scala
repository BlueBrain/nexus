package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import monix.bio.IO

object RemoteDiskStorageSaveFile extends SaveFile {
  override def apply(
      description: FileDescription,
      source: AkkaSource
  ): IO[StorageRejection.SaveFileRejection, FileAttributes] = ???
}
