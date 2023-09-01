package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import monix.bio.IO

trait FetchFile {

  /**
    * Fetches the file with the passed parameters.
    *
    * @param attributes
    *   the file attributes
    */
  def apply(attributes: FileAttributes): IO[FetchFileRejection, AkkaSource]

  /**
    * Fetches the file with the passed parameters.
    *
    * @param path
    *   the file path
    */
  def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource]
}

object FetchFile {

  /**
    * Construct a [[FetchFile]] from the given ''storage''.
    */
  def apply(storage: Storage, client: RemoteDiskStorageClient, config: StorageTypeConfig)(implicit
      as: ActorSystem
  ): FetchFile =
    storage match {
      case storage: Storage.DiskStorage       => storage.fetchFile
      case storage: Storage.S3Storage         => storage.fetchFile(config)
      case storage: Storage.RemoteDiskStorage => storage.fetchFile(client)
    }

}
