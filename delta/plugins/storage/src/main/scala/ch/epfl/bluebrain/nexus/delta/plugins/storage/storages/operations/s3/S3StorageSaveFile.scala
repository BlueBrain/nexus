package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._

import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileDescription}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.{digestSink, intermediateFolders, sizeSink}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils

import scala.concurrent.Future

final class S3StorageSaveFile(storage: S3Storage, config: StorageTypeConfig)(implicit
    as: ActorSystem
) extends SaveFile {
  import as.dispatcher
  private val fileAlreadyExistException = new IllegalArgumentException("Collision, file already exist")

  override def apply(
      description: FileDescription,
      entity: BodyPartEntity
  ): IO[FileAttributes] = {
    val attributes = S3Attributes.settings(storage.value.alpakkaSettings(config))
    val path       = Uri.Path(intermediateFolders(storage.project, description.uuid, description.filename))
    val key        = path.toString
    def s3Sink     = S3.multipartUpload(storage.value.bucket, key).withAttributes(attributes)
    IO.fromFuture(
      IO.delay(
        S3.getObjectMetadata(storage.value.bucket, key)
          .withAttributes(attributes)
          .runWith(Sink.last)
          .flatMap {
            case None    =>
              entity.dataBytes.runWith(SinkUtils.combineMat(digestSink(storage.value.algorithm), sizeSink, s3Sink) {
                case (digest, bytes, s3Result) =>
                  Future.successful(
                    FileAttributes(
                      uuid = description.uuid,
                      location = s3Result.location.withPath(Slash(path)),
                      path = Uri.Path(key),
                      filename = description.filename,
                      mediaType = description.mediaType,
                      bytes = bytes,
                      digest = digest,
                      origin = Client
                    )
                  )
              })
            case Some(_) => Future.failed(fileAlreadyExistException)
          }
      )
    ).adaptError {
      case `fileAlreadyExistException` => ResourceAlreadyExists(key)
      case err: S3Exception            => UnexpectedSaveError(key, err.toString())
      case err                         => UnexpectedSaveError(key, err.getMessage)
    }
  }
}
