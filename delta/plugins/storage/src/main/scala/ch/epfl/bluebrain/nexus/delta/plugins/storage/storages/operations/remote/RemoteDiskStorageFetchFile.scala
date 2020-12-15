package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.remote

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import monix.bio.IO

object RemoteDiskStorageFetchFile extends FetchFile {

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] = ???
}
