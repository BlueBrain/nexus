package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
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
  def apply(storage: Storage, remoteClient: RemoteDiskStorageClient, s3Client: S3StorageClient): FetchFile =
    storage match {
      case storage: Storage.DiskStorage       => storage.fetchFile
      case storage: Storage.S3Storage         => storage.fetchFile(s3Client)
      case storage: Storage.RemoteDiskStorage => storage.fetchFile(remoteClient)
    }

}
