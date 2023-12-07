package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.disk

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.FileIO
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedLocationFormat
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource

object DiskStorageFetchFile extends FetchFile {

  override def apply(attributes: FileAttributes): IO[AkkaSource] =
    apply(attributes.location.path)

  override def apply(path: Uri.Path): IO[AkkaSource] =
    absoluteDiskPath(path).redeemWith(
      e => IO.raiseError(UnexpectedLocationFormat(s"file://$path", e.getMessage)),
      path =>
        IO.raiseWhen(!path.toFile.exists())(FetchFileRejection.FileNotFound(path.toString))
          .map(_ => FileIO.fromPath(path))
    )
}
