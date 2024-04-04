package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.{BodyPartEntity, Uri}
import akka.stream.scaladsl.Source
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin.Client
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{Digest, FileStorageMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.SaveFile.intermediateFolders
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.SaveFileRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import eu.timepit.refined.types.string.NonEmptyString
import fs2.aws.s3.S3
import fs2.aws.s3.S3.MultipartETagValidation
import fs2.aws.s3.models.Models.{BucketName, ETag, FileKey, PartSizeMB}
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest

import java.util.UUID

final class S3StorageSaveFile2(client: S3AsyncClientOp[IO], storage: S3Storage)(implicit
    as: ActorSystem,
    uuidf: UUIDF
) {
  private val fileAlreadyExistException = new IllegalArgumentException("Collision, file already exist")

  def apply(
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
    val key                                   = path.toString()
    val s3                                    = S3.create(client)
    val convertedStream: fs2.Stream[IO, Byte] = StreamConverter(
      entity.dataBytes.flatMapConcat(x => Source.fromIterator(() => x.iterator)).mapMaterializedValue(_ => NotUsed)
    )

    // TODO where to get the etag returned?
    val thing: IO[Option[Option[ETag]]] = convertedStream
      .through(
        s3.uploadFileMultipart(
          BucketName(NonEmptyString.unsafeFrom(storage.value.bucket)),
          FileKey(NonEmptyString.unsafeFrom(key)),
          PartSizeMB.unsafeFrom(5),
          multipartETagValidation = MultipartETagValidation.create[IO].some
        )
      )
      .compile
      .last
    final case class Attr(fileSize: Long, checksum: String)

    // todo not sure this library sets the checksum on the object
    val getSize: IO[Attr] =
      client
        .getObjectAttributes(GetObjectAttributesRequest.builder().bucket(storage.value.bucket).key(key).build())
        .map(x => Attr(x.objectSize(), x.checksum().checksumSHA256()))

    val otherThing: IO[FileStorageMetadata] = thing.flatMap { a =>
      a.flatten match {
        case Some(_) =>
          getSize.map { case Attr(fileSize, checksum) =>
            FileStorageMetadata(
              uuid = uuid,
              bytes = fileSize,
              // TODO the digest for multipart uploads is a concatenation
              // Add this as an option to the ADT
              // https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html#large-object-checksums
              digest = Digest.ComputedDigest(DigestAlgorithm.default, checksum),
              origin = Client,
              location = Uri(key), // both of these are absolute URIs?
              path = Uri.Path(key)
            )
          }
        case None    => IO.raiseError(UnexpectedSaveError(key, "S3 multipart upload did not complete"))
      }

    }

    otherThing
      .adaptError {
        case `fileAlreadyExistException` => ResourceAlreadyExists(key)
        case err                         => UnexpectedSaveError(key, err.getMessage)
      }
  }

}
