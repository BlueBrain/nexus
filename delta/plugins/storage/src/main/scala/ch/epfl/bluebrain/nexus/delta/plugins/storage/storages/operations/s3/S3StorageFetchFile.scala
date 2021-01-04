package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.scaladsl.Sink
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FetchFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import monix.bio.Cause.Error
import monix.bio.IO

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

final class S3StorageFetchFile(value: S3StorageValue)(implicit as: ActorSystem) extends FetchFile {

  private val s3Attributes = S3Attributes.settings(value.toAlpakkaSettings)

  override def apply(path: Uri.Path): IO[FetchFileRejection, AkkaSource] =
    IO.deferFuture(
      S3.download(value.bucket, URLDecoder.decode(path.toString, UTF_8.toString))
        .withAttributes(s3Attributes)
        .runWith(Sink.head)
    ).redeemCauseWith(
      {
        case Error(err: S3Exception) => IO.raiseError(UnexpectedFetchError(path.toString, err.toString()))
        case err                     => IO.raiseError(UnexpectedFetchError(path.toString, err.toThrowable.getMessage))
      },
      {
        case Some((source, _)) => IO.pure(source: AkkaSource)
        case None              => IO.raiseError(FileNotFound(path.toString()))
      }
    )
}
