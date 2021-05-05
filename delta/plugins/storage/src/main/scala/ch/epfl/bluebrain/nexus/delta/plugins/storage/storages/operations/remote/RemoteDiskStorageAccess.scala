package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO

class RemoteDiskStorageAccess(implicit config: StorageTypeConfig, httpClient: HttpClient, as: ActorSystem)
    extends StorageAccess {
  override type Storage = RemoteDiskStorageValue

  override def apply(id: Iri, storage: RemoteDiskStorageValue): IO[StorageNotAccessible, Unit] = {
    implicit val cred: Option[AuthToken] = storage.authToken(config)
    val client: RemoteDiskStorageClient  = new RemoteDiskStorageClient(storage.endpoint)
    client
      .exists(storage.folder)
      .mapError(err => StorageNotAccessible(id, err.details.getOrElse(s"Folder '${storage.folder}' does not exist")))
  }
}
