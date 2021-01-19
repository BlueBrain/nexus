package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.Sink
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.{digestSink, intermediateFolders, sizeSink}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils
import monix.bio.IO

import scala.concurrent.Future

final class S3StorageSaveFile(storage: S3Storage)(implicit as: ActorSystem) extends SaveFile {
  import as.dispatcher
  private val fileAlreadyExistException = new IllegalArgumentException("Collision, file already exist")

  override def apply(
      description: FileDescription,
      source: AkkaSource
  ): IO[SaveFileRejection, FileAttributes] = {
    val attributes = S3Attributes.settings(storage.value.toAlpakkaSettings)
    val path       = intermediateFolders(storage.project, description.uuid, description.filename)
    val key        = path.toString
    def s3Sink     = S3.multipartUpload(storage.value.bucket, key).withAttributes(attributes)
    IO.deferFuture(
      S3.getObjectMetadata(storage.value.bucket, key)
        .withAttributes(attributes)
        .runWith(Sink.last)
        .flatMap {
          case None    =>
            source.runWith(SinkUtils.combineMat(digestSink(storage.value.algorithm), sizeSink, s3Sink) {
              case (digest, bytes, s3Result) =>
                Future.successful(
                  FileAttributes(
                    uuid = description.uuid,
                    location = s3Result.location.withPath(Slash(path)),
                    path = Uri.Path(key),
                    filename = description.filename,
                    mediaType = description.defaultMediaType,
                    bytes = bytes,
                    digest = digest,
                    origin = Client
                  )
                )
            })
          case Some(_) => Future.failed(fileAlreadyExistException)
        }
    ).leftMap {
      case `fileAlreadyExistException` => FileAlreadyExists(key)
      case err: S3Exception            => UnexpectedSaveError(key, err.toString())
      case err                         => UnexpectedSaveError(key, err.getMessage)
    }
  }
}
