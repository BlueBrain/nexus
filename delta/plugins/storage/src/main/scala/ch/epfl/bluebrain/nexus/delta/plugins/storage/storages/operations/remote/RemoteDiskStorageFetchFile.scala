package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO
import monix.execution.Scheduler

class RemoteDiskStorageFetchFile(value: RemoteDiskStorageValue)(implicit as: ActorSystem, sc: Scheduler)
    extends FetchFile {
  implicit private val cred: Option[AuthToken] = value.credentials.map(secret => AuthToken(secret.value))
  private val client: RemoteDiskStorageClient  = RemoteDiskStorageClient(value.endpoint)

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] = client.getFile(value.folder, path)
}
