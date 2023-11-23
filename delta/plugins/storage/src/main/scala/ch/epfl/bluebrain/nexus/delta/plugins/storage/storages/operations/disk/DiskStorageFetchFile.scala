package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

import java.net.URI
import java.nio.file.Paths
import scala.util.{Failure, Success, Try}

object DiskStorageFetchFile extends FetchFile {

  override def apply(attributes: FileAttributes): IO[AkkaSource] =
    apply(attributes.location.path)

  override def apply(path: Uri.Path): IO[AkkaSource] =
    Try(Paths.get(URI.create(s"file://$path"))) match {
      case Failure(err)  => IO.raiseError(UnexpectedLocationFormat(s"file://$path", err.getMessage))
      case Success(path) =>
        IO.raiseWhen(!path.toFile.exists())(FetchFileRejection.FileNotFound(path.toString)) >> IO.blocking(
          FileIO.fromPath(path)
        )
    }
}
