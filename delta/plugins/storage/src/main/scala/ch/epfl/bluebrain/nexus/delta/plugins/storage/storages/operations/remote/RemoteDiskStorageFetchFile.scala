package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import monix.bio.IO

class RemoteDiskStorageFetchFile(value: RemoteDiskStorageValue, client: RemoteDiskStorageClient) extends FetchFile {

  override def apply(attributes: FileAttributes): IO[FetchFileRejection, AkkaSource] =
    apply(attributes.path)

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    client.getFile(value.folder, path)(value.endpoint)
}
