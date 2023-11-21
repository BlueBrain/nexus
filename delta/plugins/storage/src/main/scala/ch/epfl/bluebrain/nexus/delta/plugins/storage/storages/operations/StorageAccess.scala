package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.{DiskStorageValue, RemoteDiskStorageValue, S3StorageValue}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk.DiskStorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.RemoteDiskStorageAccess
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3StorageAccess
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri

private[operations] trait StorageAccess {

  type Storage <: StorageValue

  /**
    * Checks whether the system has access to the passed ''storage''
    *
    * @return
    *   a [[Unit]] if access has been verified successfully or signals an error [[StorageNotAccessible]] with the
    *   details about why the storage is not accessible
    */
  def apply(id: Iri, storage: Storage): IO[Unit]
}

object StorageAccess {

  final private[storage] def apply(
      id: Iri,
      storage: StorageValue,
      client: RemoteDiskStorageClient,
      config: StorageTypeConfig
  )(implicit as: ActorSystem): IO[Unit] =
    storage match {
      case storage: DiskStorageValue       => DiskStorageAccess(id, storage)
      case storage: S3StorageValue         => new S3StorageAccess(config).apply(id, storage)
      case storage: RemoteDiskStorageValue => new RemoteDiskStorageAccess(client).apply(id, storage)
    }
}
