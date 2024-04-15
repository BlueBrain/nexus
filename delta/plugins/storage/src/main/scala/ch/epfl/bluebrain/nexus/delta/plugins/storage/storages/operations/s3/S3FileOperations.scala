package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection.UnexpectedFetchError
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.DurationInt

trait S3FileOperations {
  def checkBucketExists(bucket: String): IO[Unit]

  def fetch(bucket: String, path: Uri.Path): IO[AkkaSource]

  def save(
      storage: S3Storage,
      filename: String,
      entity: BodyPartEntity
  ): IO[FileStorageMetadata]
}

object S3FileOperations {

  def mk(client: S3StorageClient)(implicit as: ActorSystem, uuidf: UUIDF): S3FileOperations = new S3FileOperations {

    private val log      = Logger[S3FileOperations]
    private val saveFile = new S3StorageSaveFile(client)

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
  }

}
