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
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.model._

import java.util.UUID

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

    val bucket = storage.value.bucket

    def storeFile(key: String, uuid: UUID, entity: BodyPartEntity): IO[FileStorageMetadata] = {
      val fileData: Stream[IO, Byte] = convertStream(entity.dataBytes)

      (for {
        _               <- log(key, s"Checking for object existence")
        _               <- validateObjectDoesNotExist(bucket, key)
        _               <- log(key, s"Beginning upload")
        (md5, fileSize) <- uploadFile(fileData, bucket, key)
        _               <- log(key, s"Finished upload. MD5: $md5")
        attr            <- fileMetadata(bucket, key, uuid, fileSize, md5)
      } yield attr)
        .onError(e => logger.error(e)("Unexpected error when storing file"))
        .adaptError { err => UnexpectedSaveError(key, err.getMessage) }
    }

    def fileMetadata(bucket: String, key: String, uuid: UUID, fileSize: Long, md5: String) =
      s3StorageClient.baseEndpoint.map { base =>
        FileStorageMetadata(
          uuid = uuid,
          bytes = fileSize,
          digest = Digest.ComputedDigest(DigestAlgorithm.MD5, md5),
          origin = Client,
          location = base / bucket / Uri.Path(key),
          path = Uri.Path(key)
        )
      }

    def log(key: String, msg: String): IO[Unit] = logger.info(s"Bucket: ${bucket}. Key: $key. $msg")

    for {
      uuid   <- uuidf()
      path    = Uri.Path(intermediateFolders(storage.project, uuid, filename))
      result <- storeFile(path.toString(), uuid, entity)
    } yield result
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

  private def uploadFile(fileData: Stream[IO, Byte], bucket: String, key: String): IO[(String, Long)] = {
    for {
      fileSizeAcc <- Ref.of[IO, Long](0L)
      md5         <- fileData
                       .evalTap(_ => fileSizeAcc.update(_ + 1))
                       .through(
                         uploadFilePipe(bucket, key)
                       )
                       .compile
                       .onlyOrError
      fileSize    <- fileSizeAcc.get
    } yield (md5, fileSize)
  }

  private def uploadFilePipe(bucket: String, key: String): Pipe[IO, Byte, String] = { in =>
    fs2.Stream.eval {
      in.compile.to(Chunk).flatMap { chunks =>
        val bs = chunks.toByteBuffer
        s3.putObject(
          PutObjectRequest
            .builder()
            .bucket(bucket)
            .key(key)
            .build(),
          AsyncRequestBody.fromByteBuffer(bs)
        ).map { response =>
          response.eTag().filter(_ != '"')
        }
      }
    }
  }

  private def getFileAttributes(bucket: String, key: String): IO[GetObjectAttributesResponse] =
    s3StorageClient.getFileAttributes(bucket, key)
}
