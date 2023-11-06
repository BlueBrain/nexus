package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import cats.effect.{ContextShift, IO}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

trait FetchFile {

  /**
    * Fetches the file with the passed parameters.
    *
    * @param attributes
    *   the file attributes
    */
  def apply(attributes: FileAttributes): IO[AkkaSource]

  /**
    * Fetches the file with the passed parameters.
    *
    * @param path
    *   the file path
    */
  def apply(path: Uri.Path): IO[AkkaSource]
}

object FetchFile {

  /**
    * Construct a [[FetchFile]] from the given ''storage''.
    */
  def apply(storage: Storage, client: RemoteDiskStorageClient, config: StorageTypeConfig)(implicit
      as: ActorSystem,
      contextShift: ContextShift[IO]
  ): FetchFile =
    storage match {
      case storage: Storage.DiskStorage       => storage.fetchFile
      case storage: Storage.S3Storage         => storage.fetchFile(config)
      case storage: Storage.RemoteDiskStorage => storage.fetchFile(client)
    }

}
