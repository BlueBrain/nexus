package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

class RemoteDiskStorageAccess(client: RemoteDiskStorageClient) extends StorageAccess {
  override type Storage = RemoteDiskStorageValue

  override def apply(id: Iri, storage: RemoteDiskStorageValue): IO[StorageNotAccessible, Unit] = {
    client
      .exists(storage.folder)(storage.endpoint)
      .mapError(err =>
        StorageNotAccessible(
          id,
          err.details.fold(s"Folder '${storage.folder}' does not exist")(d => s"${err.reason}: $d")
        )
      )
  }
}
