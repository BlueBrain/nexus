package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError

class RemoteDiskStorageAccess(client: RemoteDiskStorageClient) {

  def apply(storage: RemoteDiskStorageValue): IO[Unit] = {
    client
      .exists(storage.folder)
      .adaptError { case err: HttpClientError =>
        StorageNotAccessible(
          err.details.fold(s"Folder '${storage.folder}' does not exist")(d => s"${err.reason}: $d")
        )
      }
  }
}
