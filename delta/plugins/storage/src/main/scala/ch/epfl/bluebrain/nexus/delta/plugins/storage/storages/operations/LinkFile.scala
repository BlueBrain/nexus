package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import monix.bio.IO

trait LinkFile {

  /**
    * Links a file from the passed ''sourcePath'' to the ''description''.
    *
    * @param sourcePath
    *   the file origin
    * @param description
    *   the file description
    */
  def apply(sourcePath: Uri.Path, description: FileDescription): IO[StorageFileRejection, FileAttributes]
}

object LinkFile {

  /**
    * Construct a [[LinkFile]] from the given ''storage''.
    */
  def apply(storage: Storage, client: RemoteDiskStorageClient, config: StorageTypeConfig)(implicit
      as: ActorSystem
  ): LinkFile =
    storage match {
      case storage: Storage.DiskStorage       => unsupported(storage.tpe)
      case storage: Storage.S3Storage         => storage.linkFile(config)
      case storage: Storage.RemoteDiskStorage => storage.linkFile(client)
    }

  private def unsupported(storageType: StorageType): LinkFile =
    (_, _) => IO.raiseError(MoveFileRejection.UnsupportedOperation(storageType))
}
