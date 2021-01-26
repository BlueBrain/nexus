package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import monix.bio.IO

trait LinkFile {

  /**
    * Links a file from the passed ''sourcePath'' to the ''description''.
    *
    * @param sourcePath      the file origin
    * @param description the file description
    */
  def apply(sourcePath: Uri.Path, description: FileDescription): IO[MoveFileRejection, FileAttributes]
}

object LinkFile {

  /**
    * Construct a [[LinkFile]] from the given ''storage''.
    */
  def apply(storage: Storage)(implicit as: ActorSystem, client: HttpClient): LinkFile =
    storage match {
      case storage: Storage.DiskStorage       => unsupported(storage.tpe)
      case storage: Storage.S3Storage         => unsupported(storage.tpe)
      case storage: Storage.RemoteDiskStorage => storage.linkFile
    }

  private def unsupported(storageType: StorageType): LinkFile =
    (_, _) => IO.raiseError(MoveFileRejection.UnsupportedOperation(storageType))
}
