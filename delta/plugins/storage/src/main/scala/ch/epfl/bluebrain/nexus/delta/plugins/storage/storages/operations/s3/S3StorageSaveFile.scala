package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path.Slash
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{S3Attributes, S3Exception}
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.StorageTypeConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.{digestSink, intermediateFolders, sizeSink}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.utils.SinkUtils

import java.util.UUID
import scala.concurrent.Future

final class S3StorageSaveFile(storage: S3Storage, config: StorageTypeConfig)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) extends SaveFile {
  import as.dispatcher
  private val fileAlreadyExistException = new IllegalArgumentException("Collision, file already exist")

  override def apply(
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] = {
    for {
      uuid   <- uuidf()
      path    = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      result <- storeFile(path, uuid, entity)
    } yield result
  }

  private def storeFile(path: Path, uuid: UUID, entity: BodyPartEntity): IO[FileStorageMetadata] = {
    val key        = path.toString()
    val attributes = S3Attributes.settings(storage.value.alpakkaSettings(config))
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
                    FileStorageMetadata(
                      uuid = uuid,
                      bytes = bytes,
                      digest = digest,
                      origin = Client,
                      location = s3Result.location.withPath(Slash(path)),
                      path = Uri.Path(key)
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
