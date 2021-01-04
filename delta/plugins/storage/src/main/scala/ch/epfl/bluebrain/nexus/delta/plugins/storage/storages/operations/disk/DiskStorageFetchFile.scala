package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import monix.bio.{IO, UIO}

import java.net.URI
import java.nio.file.Paths
import scala.util.{Failure, Success, Try}

object DiskStorageFetchFile extends FetchFile {

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    Try(Paths.get(URI.create(s"file://$path"))) match {
      case Failure(err)   => IO.raiseError(UnexpectedLocationFormat(s"file://$path", err.getMessage))
      case Success(value) => UIO.pure(FileIO.fromPath(value))
    }
}
