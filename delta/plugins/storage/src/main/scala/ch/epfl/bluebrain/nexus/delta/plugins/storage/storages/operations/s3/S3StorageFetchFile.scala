package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationInt

final class S3StorageFetchFile(client: S3StorageClient, value: S3StorageValue) extends FetchFile {

  override def apply(attributes: FileAttributes): IO[AkkaSource] =
    apply(attributes.path)

  override def apply(path: Uri.Path): IO[AkkaSource] = {
    IO.delay(
      Source.fromGraph(
        StreamConverter(
          client
            .readFile(value.bucket, URLDecoder.decode(path.toString, UTF_8.toString))
            .groupWithin(8192, 1.second)
            .map(bytes => ByteString(bytes.toArray))
        )
      )
    ).recoverWith { err =>
      IO.raiseError(UnexpectedFetchError(path.toString, err.getMessage))
    }
  }
}
