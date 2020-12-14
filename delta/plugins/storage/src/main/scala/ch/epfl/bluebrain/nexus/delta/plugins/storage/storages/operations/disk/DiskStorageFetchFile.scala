package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.FetchFileRejection.UnexpectedFileLocation
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import monix.bio.{IO, UIO}

import java.net.URI
import java.nio.file.Paths
import scala.util.{Failure, Success, Try}

object DiskStorageFetchFile extends FetchFile {

  override def apply(storageId: Iri, attributes: FileAttributes): IO[FetchFileRejection, AkkaSource] =
    apply(storageId, attributes.path, attributes.location.path)

  override def apply(storageId: Iri, publicPath: Uri.Path, internalPath: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    Try(Paths.get(URI.create(s"file://$internalPath"))) match {
      case Failure(err)   =>
        val errMsg = s"Couldn't create a nio Path due to '${err.getMessage}'"
        IO.raiseError(UnexpectedFileLocation(storageId, publicPath, internalPath, errMsg))
      case Success(value) =>
        UIO.pure(FileIO.fromPath(value))
    }
}
