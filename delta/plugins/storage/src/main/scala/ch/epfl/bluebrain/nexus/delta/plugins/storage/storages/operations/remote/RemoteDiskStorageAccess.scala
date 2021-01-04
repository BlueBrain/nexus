package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.actor.ActorSystem
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.AuthToken
import monix.bio.IO
import monix.execution.Scheduler

class RemoteDiskStorageAccess(implicit as: ActorSystem, sc: Scheduler) extends StorageAccess {
  override type Storage = RemoteDiskStorageValue

  override def apply(id: Iri, storage: RemoteDiskStorageValue): IO[StorageNotAccessible, Unit] = {
    implicit val cred: Option[AuthToken] = storage.credentials.map(secret => AuthToken(secret.value))
    val client: RemoteDiskStorageClient  = RemoteDiskStorageClient(storage.endpoint)
    client
      .exists(storage.folder)
      .leftMap(err => StorageNotAccessible(id, err.details.getOrElse(s"Folder '${storage.folder}' does not exist")))
  }
}
