package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{ComputedFileAttributes, FileAttributes}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{Storage, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import monix.bio.IO

trait FetchAttributes {

  /**
    * Fetches the file attributes with the passed parameters.
    *
    * @param attributes   the file attributes
    */
  def apply(attributes: FileAttributes): IO[StorageFileRejection.FetchAttributeRejection, ComputedFileAttributes] =
    apply(attributes.path).map { case RemoteDiskStorageFileAttributes(_, bytes, digest, mediaType) =>
      ComputedFileAttributes(mediaType, bytes, digest)
    }

  /**
    * Fetches the file attributes with the passed parameters.
    *
    * @param path   the file path
    */
  def apply(path: Uri.Path): IO[FetchAttributeRejection, RemoteDiskStorageFileAttributes]
}

object FetchAttributes {

  /**
    * Construct a [[FetchAttributes]] from the given ''storage''.
    */
  def apply(
      storage: Storage
  )(implicit config: StorageTypeConfig, as: ActorSystem, client: HttpClient): FetchAttributes =
    storage match {
      case storage: Storage.DiskStorage       => unsupported(storage.tpe)
      case storage: Storage.S3Storage         => unsupported(storage.tpe)
      case storage: Storage.RemoteDiskStorage => storage.fetchComputedAttributes
    }

  private def unsupported(storageType: StorageType): FetchAttributes =
    _ => IO.raiseError(FetchAttributeRejection.UnsupportedOperation(storageType))
}
