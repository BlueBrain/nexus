package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO

class RemoteDiskStorageFetchFile(value: RemoteDiskStorageValue)(implicit
    config: StorageTypeConfig,
    httpClient: HttpClient,
    as: ActorSystem
) extends FetchFile {

  implicit private val cred: Option[AuthToken] = value.authToken(config)
  private val client: RemoteDiskStorageClient  = new RemoteDiskStorageClient(value.endpoint)

  override def apply(attributes: FileAttributes): IO[FetchFileRejection, AkkaSource] =
    apply(attributes.path)

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    client.getFile(value.folder, path)
}
