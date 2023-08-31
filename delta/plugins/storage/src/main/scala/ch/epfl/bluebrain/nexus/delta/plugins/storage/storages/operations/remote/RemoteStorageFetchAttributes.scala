package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{FetchAttributes, StorageFileRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import monix.bio.IO

class RemoteStorageFetchAttributes(
    value: RemoteDiskStorageValue
)(implicit authProvider: RemoteStorageAuthTokenProvider, httpClient: HttpClient, as: ActorSystem)
    extends FetchAttributes {
  private val client: RemoteDiskStorageClient = new RemoteDiskStorageClient(value.endpoint)

  def apply(path: Uri.Path): IO[StorageFileRejection.FetchAttributeRejection, RemoteDiskStorageFileAttributes] =
    client.getAttributes(value.folder, path).mapError(WrappedFetchRejection)
}
