package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

object DiskStorageFetchFile {
  def apply(path: Uri.Path): IO[AkkaSource] =
    absoluteDiskPath(path).redeemWith(
      e => IO.raiseError(UnexpectedLocationFormat(s"file://$path", e.getMessage)),
      path =>
        IO.blocking(path.toFile.exists()).flatMap { exists =>
          if (exists) IO.blocking(FileIO.fromPath(path))
          else IO.raiseError(FetchFileRejection.FileNotFound(path.toString))
        }
    )
}
