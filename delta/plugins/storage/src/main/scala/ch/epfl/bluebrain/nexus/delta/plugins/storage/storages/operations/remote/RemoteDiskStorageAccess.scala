package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.Decrypted
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

object RemoteDiskStorageAccess extends StorageAccess {
  override type Storage = RemoteDiskStorageValue[Decrypted]

  override def apply(id: Iri, storage: RemoteDiskStorageValue[Decrypted]): IO[StorageNotAccessible, Unit] =
    IO.unit
}
