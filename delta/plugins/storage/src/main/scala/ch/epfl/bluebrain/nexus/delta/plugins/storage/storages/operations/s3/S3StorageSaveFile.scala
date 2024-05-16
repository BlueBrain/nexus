package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, StatusCodes, Uri}
import akka.stream.scaladsl.Source
import akka.stream.{Graph, SourceShape}
import akka.util.ByteString
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{UUIDF, UrlUtils}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import fs2.Stream
import software.amazon.awssdk.services.s3.model.S3Exception

import java.nio.ByteBuffer
import java.util.UUID

final class S3StorageSaveFile(s3StorageClient: S3StorageClient, locationGenerator: S3LocationGenerator)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) {

  private val logger = Logger[S3StorageSaveFile]

  def save(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity,
      contentLength: Long
  ): IO[FileStorageMetadata] =
    uuidf().flatMap { uuid =>
      val location = locationGenerator.file(storage.project, uuid, filename)
      storeFile(storage.value.bucket, location, uuid, entity, contentLength)
    }

  private def storeFile(
      bucket: String,
      location: Uri,
      uuid: UUID,
      entity: BodyPartEntity,
      contentLength: Long
  ): IO[FileStorageMetadata] = {
    val key = UrlUtils.decode(location.path)
    (for {
      _             <- log(bucket, key, s"Checking for object existence")
      _             <- validateObjectDoesNotExist(bucket, key)
      _             <- log(bucket, key, s"Beginning upload")
      fileData      <- convertStream(entity.dataBytes)
      (duration, _) <- s3StorageClient.uploadFile(fileData, bucket, key, contentLength).timed
      _             <-
        log(bucket, key, s"Finished upload for $location and $contentLength bytes after ${duration.toSeconds} seconds.")
      headResponse  <- s3StorageClient.headObject(bucket, key)
      attr           = fileMetadata(location, uuid, headResponse)
    } yield attr)
      .onError(e => logger.error(e)("Unexpected error when storing file"))
      .adaptError {
        case e: SaveFileRejection                                               => e
        case e: S3Exception if e.statusCode() == StatusCodes.Forbidden.intValue =>
          BucketAccessDenied(bucket, key, e.getMessage)
        case e                                                                  => UnexpectedSaveError(key, e.getMessage)
      }
  }

  private def fileMetadata(
      location: Uri,
      uuid: UUID,
      headResponse: HeadObject
  ): FileStorageMetadata =
    FileStorageMetadata(
      uuid = uuid,
      bytes = headResponse.fileSize,
      digest = headResponse.digest,
      origin = Client,
      location = location,
      path = location.path
    )

  private def validateObjectDoesNotExist(bucket: String, key: String) =
    s3StorageClient
      .objectExists(bucket, key)
      .flatMap {
        case true  => IO.raiseError(ResourceAlreadyExists(key))
        case false => IO.unit
      }

  private def convertStream(source: Source[ByteString, Any]): IO[Stream[IO, ByteBuffer]] = IO.delay {
    StreamConverter(source.asInstanceOf[Graph[SourceShape[ByteString], NotUsed]]).map { byteString =>
      byteString.asByteBuffer
    }
  }

  private def log(bucket: String, key: String, msg: String): IO[Unit] =
    logger.info(s"Bucket: $bucket. Key: $key. $msg")
}
