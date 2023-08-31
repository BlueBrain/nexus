package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FetchAttributes, StorageFileRejection}
import monix.bio.IO

class RemoteStorageFetchAttributes(
    value: RemoteDiskStorageValue,
    client: RemoteDiskStorageClient
) extends FetchAttributes {
  def apply(path: Uri.Path): IO[StorageFileRejection.FetchAttributeRejection, RemoteDiskStorageFileAttributes] =
    client.getAttributes(value.folder, path)(value.endpoint).mapError(WrappedFetchRejection)
}
