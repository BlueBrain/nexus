package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, ContentType, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.RegisterFileRejection.InvalidContentType
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.S3FileMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.HeadObject
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import org.apache.commons.codec.binary.Hex

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import scala.concurrent.duration.DurationInt

trait S3FileOperations {
  def checkBucketExists(bucket: String): IO[Unit]

  def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]

  def save(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata]

  def register(bucket: String, path: Uri.Path): IO[S3FileMetadata]
}

object S3FileOperations {
  final case class S3FileMetadata(contentType: Option[ContentType], metadata: FileStorageMetadata)

  private val log = Logger[S3FileOperations]

  def mk(client: S3StorageClient)(implicit as: ActorSystem, uuidf: UUIDF): S3FileOperations = new S3FileOperations {

    private lazy val saveFile = new S3StorageSaveFile(client)

    override def checkBucketExists(bucket: String): IO[Unit] = {
      client.bucketExists(bucket).flatMap {
        case true  => IO.unit
        case false => IO.raiseError(StorageNotAccessible(s"Bucket $bucket does not exist"))
      }
    }

    override def fetch(bucket: String, path: Uri.Path): IO[AkkaSource] = IO
      .delay(
        Source.fromGraph(
          StreamConverter(
            client
              .readFile(bucket, URLDecoder.decode(path.toString, UTF_8.toString))
              .groupWithin(8192, 1.second)
              .map(bytes => ByteString(bytes.toArray))
          )
        )
      )
      .recoverWith { err =>
        IO.raiseError(UnexpectedFetchError(path.toString, err.getMessage))
      }

    override def save(storage: S3Storage, filename: String, entity: BodyPartEntity): IO[FileStorageMetadata] =
      saveFile.apply(storage, filename, entity)

    override def register(bucket: String, path: Uri.Path): IO[S3FileMetadata] =
      registerInternal(client, bucket, path)

  }

  def registerInternal(client: S3StorageClient, bucket: String, path: Uri.Path)(implicit
      uuidF: UUIDF
  ): IO[S3FileMetadata] = {
    for {
      _        <- log.info(s"Fetching attributes for S3 file. Bucket $bucket at path $path")
      resp     <- client.headObject(bucket, path.toString())
      metadata <- mkS3Metadata(client, bucket, path, resp)
    } yield metadata
  }
    .onError { e =>
      log.error(e)(s"Failed fetching required attributes for S3 file registration. Bucket $bucket and path $path")
    }

  private def parseContentType(raw: Option[String]): IO[Option[ContentType]] = {
    raw match {
      case Some(value) =>
        ContentType.parse(value) match {
          case Left(_)      => IO.raiseError(InvalidContentType(value))
          case Right(value) => IO.pure(Some(value))
        }
      case None        => IO.none
    }
  }

  private def mkS3Metadata(client: S3StorageClient, bucket: String, path: Uri.Path, resp: HeadObject)(implicit
      uuidf: UUIDF
  ) = {
    for {
      uuid        <- uuidf()
      contentType <- parseContentType(resp.contentType)
      checksum    <- checksumFrom(resp)
    } yield S3FileMetadata(
      contentType,
      FileStorageMetadata(
        uuid,
        resp.fileSize,
        checksum,
        FileAttributesOrigin.External,
        client.baseEndpoint / bucket / path,
        path
      )
    )
  }

  private def checksumFrom(response: HeadObject) = IO.fromOption {
    response.sha256Checksum
      .map { checksum =>
        Digest.ComputedDigest(
          DigestAlgorithm.default,
          Hex.encodeHexString(Base64.getDecoder.decode(checksum))
        )
      }
  }(new IllegalArgumentException("Missing checksum"))

}
