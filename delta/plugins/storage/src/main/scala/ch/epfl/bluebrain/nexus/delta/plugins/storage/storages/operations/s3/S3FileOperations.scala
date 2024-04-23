package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, ContentType, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.RegisterFileRejection.{InvalidContentType, MissingChecksum}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.S3FileOperations.S3FileMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import org.apache.commons.codec.binary.Hex
import software.amazon.awssdk.services.s3.model.HeadObjectResponse

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

  def registerUpdate(storage: S3Storage, path: Uri.Path, entity: BodyPartEntity): IO[FileStorageMetadata]
}

object S3FileOperations {
  final case class S3FileMetadata(contentType: ContentType, metadata: FileStorageMetadata)

  def mk(client: S3StorageClient)(implicit as: ActorSystem, uuidf: UUIDF): S3FileOperations = new S3FileOperations {

    private val log           = Logger[S3FileOperations]
    private lazy val saveFile = new S3StorageSaveFile(client)

    override def checkBucketExists(bucket: String): IO[Unit] =
      client
        .listObjectsV2(bucket)
        .redeemWith(
          err => IO.raiseError(StorageNotAccessible(err.getMessage)),
          response => log.info(s"S3 bucket $bucket contains ${response.keyCount()} objects")
        )

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
      saveFile.saveNewFile(storage, filename, entity)

    override def register(bucket: String, path: Uri.Path): IO[S3FileMetadata] = {
      for {
        _           <- log.info(s"Fetching attributes for S3 file. Bucket $bucket at path $path")
        resp        <- client.headObject(bucket, path.toString())
        contentType <- parseContentType(resp.contentType())
        metadata    <- mkS3Metadata(bucket, path, resp, contentType)
      } yield metadata
    }
      .onError { e =>
        log.error(e)(s"Failed fetching required attributes for S3 file registration. Bucket $bucket and path $path")
      }

    override def registerUpdate(storage: S3Storage, path: Uri.Path, entity: BodyPartEntity): IO[FileStorageMetadata] =
      saveFile.overwriteFile(storage, path, entity)

    private def parseContentType(raw: String): IO[ContentType] =
      ContentType.parse(raw).map(_.pure[IO]).getOrElse(IO.raiseError(InvalidContentType(raw)))

    private def mkS3Metadata(bucket: String, path: Uri.Path, resp: HeadObjectResponse, ct: ContentType) =
      for {
        uuid     <- uuidf()
        checksum <- checksumFrom(resp, path.toString())
      } yield S3FileMetadata(
        ct,
        FileStorageMetadata(
          uuid,
          resp.contentLength(),
          checksum,
          FileAttributesOrigin.External,
          client.baseEndpoint / bucket / path,
          path
        )
      )

    private def checksumFrom(response: HeadObjectResponse, key: String) = IO.fromOption {
      Option(response.checksumSHA256())
        .map { checksum =>
          Digest.ComputedDigest(
            DigestAlgorithm.default,
            Hex.encodeHexString(Base64.getDecoder.decode(checksum))
          )
        }
    }(MissingChecksum(key))
  }

}
