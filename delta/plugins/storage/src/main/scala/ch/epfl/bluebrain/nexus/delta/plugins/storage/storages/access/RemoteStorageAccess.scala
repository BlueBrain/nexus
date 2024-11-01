package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import cats.syntax.all._
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label

trait RemoteStorageAccess {

  def checkFolderExists(folder: Label): IO[Unit]

}

object RemoteStorageAccess {

  def apply(client: RemoteDiskStorageClient): RemoteStorageAccess =
    (folder: Label) =>
      client
        .exists(folder)
        .adaptError { case err: HttpClientError =>
          StorageNotAccessible(
            err.details.fold(s"Folder '$folder' does not exist")(d => s"${err.reason}: $d")
          )
        }

}
