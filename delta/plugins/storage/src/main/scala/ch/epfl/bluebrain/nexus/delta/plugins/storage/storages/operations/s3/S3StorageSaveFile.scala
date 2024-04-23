package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.FileOperations.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.UploadMetadata
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import fs2.Stream

import java.util.UUID

final class S3StorageSaveFile(s3StorageClient: S3StorageClient)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) {

  private val logger = Logger[S3StorageSaveFile]

  def saveNewFile(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] =
    for {
      uuid  <- uuidf()
      path   = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      bucket = storage.value.bucket
      key    = path.toString()
      _     <- log(bucket, key, s"Checking object does not exist in S3")
      _     <- validateObjectDoesNotExist(bucket, key)
      attr  <- uploadFile(bucket, key, uuid, entity, storage.value.algorithm)
    } yield attr

  def overwriteFile(
      storage: S3Storage,
      path: Uri.Path,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] = {
    val bucket = storage.value.bucket
    val key    = path.toString()
    for {
      _      <- log(bucket, key, s"Checking object exists in S3")
      _      <- validateObjectExists(storage.value.bucket, path.toString())
      uuid   <- uuidf()
      result <- uploadFile(storage.value.bucket, path.toString(), uuid, entity, storage.value.algorithm)
    } yield result
  }

  private def uploadFile(
      bucket: String,
      key: String,
      uuid: UUID,
      entity: BodyPartEntity,
      algorithm: DigestAlgorithm
  ): IO[FileStorageMetadata] = {
    val fileData: Stream[IO, Byte] = convertStream(entity.dataBytes)

    (for {
      _              <- log(bucket, key, s"Beginning upload")
      uploadMetadata <- s3StorageClient.uploadFile(fileData, bucket, key, algorithm)
      _              <- log(bucket, key, s"Finished upload. Digest: ${uploadMetadata.checksum}")
      attr            = fileMetadata(key, uuid, algorithm, uploadMetadata)
    } yield attr)
      .onError(e => logger.error(e)(s"Unexpected error when uploading file with key $key to bucket $bucket"))
      .adaptError { err => UnexpectedSaveError(key, err.getMessage) }
  }

  private def fileMetadata(
      key: String,
      uuid: UUID,
      algorithm: DigestAlgorithm,
      uploadMetadata: UploadMetadata
  ): FileStorageMetadata =
    FileStorageMetadata(
      uuid = uuid,
      bytes = uploadMetadata.fileSize,
      digest = Digest.ComputedDigest(algorithm, uploadMetadata.checksum),
      origin = Client,
      location = uploadMetadata.location,
      path = Uri.Path(key)
    )

  private def validateObjectExists(bucket: String, key: String) =
    s3StorageClient.objectExists(bucket, key).ifM(IO.unit, IO.raiseError(FileNotFound(key)))

  private def validateObjectDoesNotExist(bucket: String, key: String) =
    s3StorageClient.objectExists(bucket, key).ifM(IO.raiseError(ResourceAlreadyExists(key)), IO.unit)

  private def convertStream(source: Source[ByteString, Any]): Stream[IO, Byte] =
    StreamConverter(
      source
        .flatMapConcat(x => Source.fromIterator(() => x.iterator))
        .mapMaterializedValue(_ => NotUsed)
    )

  private def log(bucket: String, key: String, msg: String): IO[Unit] =
    logger.info(s"Bucket: $bucket. Key: $key. $msg")
}
