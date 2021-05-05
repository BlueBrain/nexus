package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.scaladsl.Sink
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.{LinkFile, SaveFile, StorageFileRejection}
import monix.bio.IO

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Future

class S3StorageLinkFile(storage: S3Storage)(implicit config: StorageTypeConfig, as: ActorSystem) extends LinkFile {

  import as.dispatcher

  private val fileNotFoundException = new IllegalArgumentException("File not found")

  override def apply(key: Uri.Path, description: FileDescription): IO[StorageFileRejection, FileAttributes] = {
    val attributes    = S3Attributes.settings(storage.value.alpakkaSettings(config))
    val location: Uri = storage.value.address(storage.value.bucket) / key
    IO.deferFuture(
      S3.download(storage.value.bucket, URLDecoder.decode(key.toString, UTF_8.toString))
        .withAttributes(attributes)
        .runWith(Sink.head)
        .flatMap {
          case Some((source, meta)) =>
            source.runWith(SaveFile.digestSink(storage.value.algorithm)).map { dig =>
              FileAttributes(
                description.uuid,
                location,
                key,
                description.filename,
                description.mediaType,
                meta.contentLength,
                dig,
                origin = Storage
              )
            }
          case None                 => Future.failed(fileNotFoundException)
        }
    ).mapError {
      case `fileNotFoundException` => FetchFileRejection.FileNotFound(location.toString)
      case err: S3Exception        => FetchFileRejection.UnexpectedFetchError(key.toString, err.toString)
      case err                     => FetchFileRejection.UnexpectedFetchError(key.toString, err.getMessage)
    }
  }

}
