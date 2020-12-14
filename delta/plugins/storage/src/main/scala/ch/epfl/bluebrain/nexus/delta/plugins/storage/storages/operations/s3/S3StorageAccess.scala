package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.EncryptionState.Decrypted
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

object S3StorageAccess extends StorageAccess {
  override type Storage = S3StorageValue[Decrypted]

  override def apply(id: Iri, storage: S3StorageValue[Decrypted]): IO[StorageNotAccessible, Unit] =
    IO.unit
}
