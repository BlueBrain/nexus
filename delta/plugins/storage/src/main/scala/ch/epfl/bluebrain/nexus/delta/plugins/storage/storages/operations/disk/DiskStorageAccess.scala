package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.Decrypted
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.DiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

import java.nio.file.Files

object DiskStorageAccess extends StorageAccess {
  override type Storage = DiskStorageValue[Decrypted]

  override def apply(id: Iri, storage: DiskStorageValue[Decrypted]): IO[StorageNotAccessible, Unit] = {
    if (!Files.exists(storage.volume))
      IO.raiseError(StorageNotAccessible(id, s"Volume '${storage.volume}' does not exist."))
    else if (!Files.isDirectory(storage.volume))
      IO.raiseError(StorageNotAccessible(id, s"Volume '${storage.volume}' is not a directory."))
    else if (!Files.isWritable(storage.volume))
      IO.raiseError(StorageNotAccessible(id, s"Volume '${storage.volume}' does not have write access."))
    else
      IO.unit
  }
}
