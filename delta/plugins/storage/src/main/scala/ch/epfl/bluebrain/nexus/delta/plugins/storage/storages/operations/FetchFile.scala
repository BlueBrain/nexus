package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import monix.bio.IO

trait FetchFile {

  /**
    * Fetches the file with the passed parameters.
    *
    * @param path   the file path
    */
  def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource]
}
