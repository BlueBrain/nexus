package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.ComputedFileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.RemoteDiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchAttributeRejection.WrappedFetchRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.model.RemoteDiskStorageFileAttributes

class RemoteStorageFetchAttributes(
    client: RemoteDiskStorageClient
) {
  def apply(storage: RemoteDiskStorage, path: Uri.Path): IO[ComputedFileAttributes] =
    client
      .getAttributes(storage.value.folder, path)
      .map { case RemoteDiskStorageFileAttributes(_, bytes, digest, mediaType) =>
        ComputedFileAttributes(mediaType, bytes, digest)
      }
      .adaptError { case e: FetchFileRejection =>
        WrappedFetchRejection(e)
      }
}
