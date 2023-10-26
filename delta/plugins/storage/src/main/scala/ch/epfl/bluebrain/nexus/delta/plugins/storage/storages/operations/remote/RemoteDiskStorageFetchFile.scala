package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.RemoteDiskStorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote.client.RemoteDiskStorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

class RemoteDiskStorageFetchFile(value: RemoteDiskStorageValue, client: RemoteDiskStorageClient) extends FetchFile {

  override def apply(attributes: FileAttributes): IO[AkkaSource] =
    apply(attributes.path)

  override def apply(path: Uri.Path): IO[AkkaSource] =
    client.getFile(value.folder, path)(value.endpoint).toCatsIO
}
