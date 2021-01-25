package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO

import scala.concurrent.ExecutionContext

class RemoteDiskStorageFetchFile(value: RemoteDiskStorageValue)(implicit httpClient: HttpClient, ec: ExecutionContext)
    extends FetchFile {
  implicit private val cred: Option[AuthToken] = value.credentials.map(secret => AuthToken(secret.value))
  private val client: RemoteDiskStorageClient  = new RemoteDiskStorageClient(value.endpoint)

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] = client.getFile(value.folder, path)
}
