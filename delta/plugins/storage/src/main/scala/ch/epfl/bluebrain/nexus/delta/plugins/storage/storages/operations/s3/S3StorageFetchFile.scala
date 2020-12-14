package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.IO

object S3StorageFetchFile extends FetchFile {

  override def apply(storageId: Iri, attributes: FileAttributes): IO[FetchFileRejection, AkkaSource] =
    ???

  override def apply(storageId: Iri, publicPath: Uri.Path, internalPath: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    ???
}
