package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.data.Validated
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
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.RegisterFileRejection.MissingS3Attributes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import scala.concurrent.duration.DurationInt

trait S3FileOperations {
  def checkBucketExists(bucket: String): IO[Unit]

  def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]

  def save(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata]

  def register(bucket: String, path: Uri.Path): IO[FileStorageMetadata]
}

object S3FileOperations {

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
      saveFile.apply(storage, filename, entity)

    override def register(bucket: String, path: Uri.Path): IO[FileStorageMetadata] =
      client.getFileAttributes(bucket, path.toString()).flatMap { resp =>
        val maybeSize     = Option(resp.objectSize()).toRight("object size").toValidatedNel
        println(maybeSize)
        val maybeEtag     = Option(resp.objectSize()).toRight("etag").toValidatedNel
        println(maybeEtag)
        val maybeChecksum = Option(resp.checksum()).toRight("checksum").toValidatedNel
        println(maybeChecksum)

        maybeSize.product(maybeEtag).product(maybeChecksum) match {
          case Validated.Valid(((size, _), checksum)) =>
            FileStorageMetadata(
              UUID.randomUUID(),
              size,
              Digest.ComputedDigest(DigestAlgorithm.MD5, checksum.toString),
              FileAttributesOrigin.External,
              location = client.baseEndpoint / bucket / path,
              path = path
            ).pure[IO]
          case Validated.Invalid(errors)              => IO.raiseError(MissingS3Attributes(errors))
        }
      }
  }

}
