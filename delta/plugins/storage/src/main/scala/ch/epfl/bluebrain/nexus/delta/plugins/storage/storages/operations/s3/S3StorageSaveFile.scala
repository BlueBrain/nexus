package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
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
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.aws.s3.S3.MultipartETagValidation
import fs2.aws.s3.models.Models.{BucketName, ETag, FileKey, PartSizeMB}
import software.amazon.awssdk.services.s3.model._

import java.util.UUID

final class S3StorageSaveFile(s3StorageClient: S3StorageClient)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) {

  private val s3                      = s3StorageClient.underlyingClient
  private val multipartETagValidation = MultipartETagValidation.create[IO]
  private val logger                  = Logger[S3StorageSaveFile]
  private val partSizeMB: PartSizeMB  = refineMV(5)
  private val bucket                  = BucketName(NonEmptyString.unsafeFrom("TODO pass this through"))

  def apply(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] = {

    def storeFile(path: Path, uuid: UUID, entity: BodyPartEntity): IO[FileStorageMetadata] = {
      val key                        = path.toString()
      val fileData: Stream[IO, Byte] = convertStream(entity.dataBytes)

      (for {
        _          <- log(key, s"Checking for object existence")
        _          <- validateObjectDoesNotExist(key)
        _          <- log(key, s"Beginning multipart upload")
        maybeEtags <- uploadFileMultipart(fileData, key)
        _          <- log(key, s"Finished multipart upload. Etag by part: $maybeEtags")
        attr       <- collectFileMetadata(fileData, key, uuid, maybeEtags)
      } yield attr)
        .onError(e => logger.error(e)("Unexpected error when storing file"))
        .adaptError { err => UnexpectedSaveError(key, err.getMessage) }
    }

    def collectFileMetadata(
        bytes: Stream[IO, Byte],
        key: String,
        uuid: UUID,
        maybeEtags: List[Option[ETag]]
    ): IO[FileStorageMetadata] =
      maybeEtags.sequence match {
        case Some(onlyPartETag :: Nil) =>
          // TODO our tests expect specific values for digests and the only algorithm currently used is SHA-256.
          // If we want to continue to check this, but allow for different algorithms, this needs to be abstracted
          // in the tests and verified for specific file contents.
          // The result will als depend on whether we use a multipart upload or a standard put object.
          for {
            _        <- log(key, s"Received ETag for single part upload: $onlyPartETag")
            fileSize <- computeSize(bytes)
            digest   <- computeDigest(bytes, storage.storageValue.algorithm)
            metadata <- fileMetadata(key, uuid, fileSize, digest)
          } yield metadata
        case Some(other)               => raiseUnexpectedErr(key, s"S3 multipart upload returned multiple etags unexpectedly: $other")
        case None                      => raiseUnexpectedErr(key, "S3 multipart upload was aborted because no data was received")
      }

    def fileMetadata(key: String, uuid: UUID, fileSize: Long, digest: String) =
      s3StorageClient.baseEndpoint.map { base =>
        FileStorageMetadata(
          uuid = uuid,
          bytes = fileSize,
          digest = Digest.ComputedDigest(storage.value.algorithm, digest),
          origin = Client,
          location = base / bucket.value.value / Uri.Path(key),
          path = Uri.Path(key)
        )
      }

    for {
      uuid   <- uuidf()
      path    = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      result <- storeFile(path, uuid, entity)
    } yield result
  }

  private def validateObjectDoesNotExist(key: String) =
    getFileAttributes(key).redeemWith(
      {
        case _: NoSuchKeyException => IO.unit
        case e                     => IO.raiseError(e)
      },
      _ => IO.raiseError(ResourceAlreadyExists(key))
    )

  private def convertStream(source: Source[ByteString, Any]): Stream[IO, Byte] =
    StreamConverter(
      source
        .flatMapConcat(x => Source.fromIterator(() => x.iterator))
        .mapMaterializedValue(_ => NotUsed)
    )

  private def uploadFileMultipart(fileData: Stream[IO, Byte], key: String): IO[List[Option[ETag]]] =
    fileData
      .through(
        s3.uploadFileMultipart(
          bucket,
          FileKey(NonEmptyString.unsafeFrom(key)),
          partSizeMB,
          uploadEmptyFiles = true,
          multipartETagValidation = multipartETagValidation.some
        )
      )
      .compile
      .to(List)

  private def getFileAttributes(key: String): IO[GetObjectAttributesResponse] =
    s3StorageClient.getFileAttributes(bucket.value.value, key)

  // TODO issue fetching attributes when tested against localstack, only after the object is saved
  // Verify if it's the same for real S3. Error msg: 'Could not parse XML response.'
  // For now we just compute it manually.
  //  private def getFileSize(key: String) =
  //      getFileAttributes(key).flatMap { attr =>
  //        log(key, s"File attributes from S3: $attr").as(attr.objectSize())
  //      }
  private def computeSize(bytes: Stream[IO, Byte]): IO[Long] = bytes.fold(0L)((acc, _) => acc + 1).compile.lastOrError

  private def computeDigest(bytes: Stream[IO, Byte], algorithm: DigestAlgorithm): IO[String] = {
    val digest = algorithm.digest
    bytes.chunks
      .evalMap(chunk => IO(digest.update(chunk.toArray)))
      .compile
      .last
      .map { _ =>
        digest.digest().map("%02x".format(_)).mkString
      }
  }

  private def raiseUnexpectedErr[A](key: String, msg: String): IO[A] = IO.raiseError(UnexpectedSaveError(key, msg))

  private def log(key: String, msg: String) = logger.info(s"Bucket: ${bucket.value}. Key: $key. $msg")
}
