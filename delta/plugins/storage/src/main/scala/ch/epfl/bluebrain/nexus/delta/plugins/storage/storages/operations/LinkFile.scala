package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.MoveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient

trait LinkFile {

  /**
    * Links a file from the passed ''sourcePath'' to the ''description''.
    *
    * @param sourcePath
    *   the file origin
    * @param description
    *   the file description
    */
  def apply(sourcePath: Uri.Path, filename: String): IO[FileStorageMetadata]
}

object LinkFile {

  /**
    * Construct a [[LinkFile]] from the given ''storage''.
    */
  def apply(storage: Storage, client: RemoteDiskStorageClient)(implicit
      uuidf: UUIDF
  ): LinkFile =
    storage match {
      case storage: Storage.RemoteDiskStorage => storage.linkFile(client)
      case _                                  => unsupported(storage.tpe)
    }

  private def unsupported(storageType: StorageType): LinkFile =
    (_, _) => IO.raiseError(MoveFileRejection.UnsupportedOperation(storageType))
}
