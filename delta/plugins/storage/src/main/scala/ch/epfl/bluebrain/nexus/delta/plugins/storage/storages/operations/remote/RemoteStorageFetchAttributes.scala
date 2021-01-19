package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO
import monix.execution.Scheduler

class RemoteStorageFetchAttributes(value: RemoteDiskStorageValue)(implicit as: ActorSystem, sc: Scheduler) {
  implicit private val cred: Option[AuthToken] = value.credentials.map(secret => AuthToken(secret.value))
  private val client: RemoteDiskStorageClient  = RemoteDiskStorageClient(value.endpoint)

  def apply(path: Uri.Path): IO[StorageFileRejection.FetchAttributeRejection, RemoteDiskStorageFileAttributes] =
    client.getAttributes(value.folder, path).mapError(WrappedFetchRejection)
}
