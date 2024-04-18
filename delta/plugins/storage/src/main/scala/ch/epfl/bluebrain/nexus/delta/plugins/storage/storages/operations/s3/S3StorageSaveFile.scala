package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.{IO, Ref}
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
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import fs2.{Chunk, Pipe, Stream}
import org.apache.commons.codec.binary.Hex
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.model._

import java.util.{Base64, UUID}

final class S3StorageSaveFile(s3StorageClient: S3StorageClient)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) {

  private val s3     = s3StorageClient.underlyingClient
  private val logger = Logger[S3StorageSaveFile]

  def apply(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata] = {

    for {
      uuid   <- uuidf()
      path    = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      result <- storeFile(storage.value.bucket, path.toString(), uuid, entity, storage.value.algorithm)
    } yield result
  }

  private def storeFile(
      bucket: String,
      key: String,
      uuid: UUID,
      entity: BodyPartEntity,
      algorithm: DigestAlgorithm
  ): IO[FileStorageMetadata] = {
    val fileData: Stream[IO, Byte] = convertStream(entity.dataBytes)

    (for {
      _                  <- log(bucket, key, s"Checking for object existence")
      _                  <- validateObjectDoesNotExist(bucket, key)
      _                  <- log(bucket, key, s"Beginning upload")
      (digest, fileSize) <- uploadFile(fileData, bucket, key, algorithm)
      _                  <- log(bucket, key, s"Finished upload. Digest: $digest")
      attr               <- fileMetadata(bucket, key, uuid, fileSize, algorithm, digest)
    } yield attr)
      .onError(e => logger.error(e)("Unexpected error when storing file"))
      .adaptError { err => UnexpectedSaveError(key, err.getMessage) }
  }

  private def fileMetadata(
      bucket: String,
      key: String,
      uuid: UUID,
      fileSize: Long,
      algorithm: DigestAlgorithm,
      digest: String
  ): IO[FileStorageMetadata] =
    s3StorageClient.baseEndpoint.map { base =>
      FileStorageMetadata(
        uuid = uuid,
        bytes = fileSize,
        digest = Digest.ComputedDigest(algorithm, digest),
        origin = Client,
        location = base / bucket / Uri.Path(key),
        path = Uri.Path(key)
      )
    }

  private def validateObjectDoesNotExist(bucket: String, key: String) =
    getFileAttributes(bucket, key).redeemWith(
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

  private def uploadFile(
      fileData: Stream[IO, Byte],
      bucket: String,
      key: String,
      algorithm: DigestAlgorithm
  ): IO[(String, Long)] = {
    for {
      fileSizeAcc <- Ref.of[IO, Long](0L)
      digest      <- fileData
                       .evalTap(_ => fileSizeAcc.update(_ + 1))
                       .through(
                         uploadFilePipe(bucket, key, algorithm)
                       )
                       .compile
                       .onlyOrError
      fileSize    <- fileSizeAcc.get
    } yield (digest, fileSize)
  }

  private def uploadFilePipe(bucket: String, key: String, algorithm: DigestAlgorithm): Pipe[IO, Byte, String] = { in =>
    fs2.Stream.eval {
      in.compile.to(Chunk).flatMap { chunks =>
        val bs      = chunks.toByteBuffer
        val request = PutObjectRequest
          .builder()
          .bucket(bucket)
          .key(key)

        for {
          fullRequest <- setAlgorithm(request, algorithm)
          response    <- s3.putObject(
                           fullRequest
                             .build(),
                           AsyncRequestBody.fromByteBuffer(bs)
                         )
        } yield {
          parseResponse(response, algorithm)
        }
      }
    }
  }

  private def parseResponse(response: PutObjectResponse, algorithm: DigestAlgorithm): String = {
    algorithm.value match {
      case "MD5"     => response.eTag().stripPrefix("\"").stripSuffix("\"")
      case "SHA-256" => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA256()))
      case "SHA-1"   => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA1()))
      case _         => throw new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}")
    }
  }

  private def setAlgorithm(
      request: PutObjectRequest.Builder,
      algorithm: DigestAlgorithm
  ): IO[PutObjectRequest.Builder] =
    algorithm.value match {
      case "MD5"     => IO.pure(request)
      case "SHA-256" => IO.delay(request.checksumAlgorithm(ChecksumAlgorithm.SHA256))
      case "SHA-1"   => IO.delay(request.checksumAlgorithm(ChecksumAlgorithm.SHA1))
      case _         => IO.raiseError(new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}"))
    }

  private def getFileAttributes(bucket: String, key: String): IO[GetObjectAttributesResponse] =
    s3StorageClient.getFileAttributes(bucket, key)

  private def log(bucket: String, key: String, msg: String): IO[Unit] =
    logger.info(s"Bucket: ${bucket}. Key: $key. $msg")
}
