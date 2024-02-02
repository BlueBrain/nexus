package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{LinkFile, SaveFile}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Future

class S3StorageLinkFile(storage: S3Storage, config: StorageTypeConfig)(implicit as: ActorSystem, uuidf: UUIDF)
    extends LinkFile {

  import as.dispatcher

  private val fileNotFoundException = new IllegalArgumentException("File not found")

  override def apply(key: Uri.Path, filename: String): IO[FileStorageMetadata] = {

    for {
      uuid                              <- uuidf()
      (contentLength, digest, location) <- storeFile(key)
    } yield {
      FileStorageMetadata(
        uuid,
        contentLength,
        digest,
        origin = Storage,
        location,
        key
      )
    }
  }

  private def storeFile(key: Uri.Path): IO[(Long, Digest.ComputedDigest, Uri)] = {
    val attributes    = S3Attributes.settings(storage.value.alpakkaSettings(config))
    val location: Uri = storage.value.address(storage.value.bucket) / key
    IO.fromFuture(
      IO.delay(
        S3.download(storage.value.bucket, URLDecoder.decode(key.toString, UTF_8.toString))
          .withAttributes(attributes)
          .runWith(Sink.head)
          .flatMap {
            case Some((source, meta)) =>
              source.runWith(SaveFile.digestSink(storage.value.algorithm)).map { dig =>
                (meta.contentLength, dig, location)
              }
            case None                 => Future.failed(fileNotFoundException)
          }
      )
    ).adaptError {
      case `fileNotFoundException` => FetchFileRejection.FileNotFound(location.toString)
      case err: S3Exception        => FetchFileRejection.UnexpectedFetchError(key.toString, err.toString)
      case err                     => FetchFileRejection.UnexpectedFetchError(key.toString, err.getMessage)
    }
  }
}
