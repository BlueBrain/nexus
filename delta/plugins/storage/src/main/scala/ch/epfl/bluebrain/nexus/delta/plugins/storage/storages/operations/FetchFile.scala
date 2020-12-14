package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

trait FetchFile {

  /**
    * Fetches the file associated to the provided ''attributes''.
    *
    * @param storageId  the storage identifier
    * @param attributes the file attributes
    */
  def apply(storageId: Iri, attributes: FileAttributes): IO[FetchFileRejection, AkkaSource]

  /**
    * Fetches the file with the passed parameters.
    *
    * @param storageId    the storage identifier
    * @param publicPath   the file path exposed on the API
    * @param internalPath the file internal path. It might be the same or different to the ''publicPath''
    */
  private[operations] def apply(
      storageId: Iri,
      publicPath: Uri.Path,
      internalPath: Uri.Path
  ): IO[FetchFileRejection, AkkaSource]
}
