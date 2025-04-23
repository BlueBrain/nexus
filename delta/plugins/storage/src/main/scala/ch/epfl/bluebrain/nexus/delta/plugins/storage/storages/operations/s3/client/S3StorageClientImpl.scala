package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.ContentType
import cats.effect.IO
import cats.implicits.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{PutObjectRequest, *}
import ch.epfl.bluebrain.nexus.delta.sdk.FileData
import eu.timepit.refined.refineMV
import eu.timepit.refined.types.string.NonEmptyString
import fs2.Stream
import fs2.aws.s3.S3
import fs2.aws.s3.models.Models.{BucketName, FileKey, PartSizeMB}
import fs2.interop.reactivestreams.{PublisherOps, *}
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import org.reactivestreams.Subscriber
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.model.*

import java.nio.ByteBuffer
import java.util.Optional
import scala.jdk.CollectionConverters.*

final private[client] class S3StorageClientImpl(client: S3AsyncClientOp[IO]) extends S3StorageClient {

  private val logger = Logger[S3StorageClientImpl]

  override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
    client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())

  override def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response] =
    client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())

  override def readFile(bucket: String, fileKey: String): FileData =
    Stream
      .eval(client.getObject(getObjectRequest(bucket, fileKey), new Fs2StreamAsyncResponseTransformer))
      .flatMap(_.toStreamBuffered[IO](2))

  override def readFileMultipart(bucket: String, fileKey: String): Stream[IO, Byte] = {
    val bucketName           = BucketName(NonEmptyString.unsafeFrom(bucket))
    val objectKey            = FileKey(NonEmptyString.unsafeFrom(fileKey))
    val partSize: PartSizeMB = refineMV(5)

    S3.create(client).readFileMultipart(bucketName, objectKey, partSize)
  }

  override def headObject(bucket: String, key: String): IO[HeadObject] =
    client
      .headObject(
        HeadObjectRequest
          .builder()
          .bucket(bucket)
          .key(key)
          .checksumMode(ChecksumMode.ENABLED)
          .build
      )
      .map(resp => HeadObject(resp))

  override def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult] =
    approveCopy(destinationBucket, destinationKey, options.overwriteTarget).flatMap { approved =>
      if (approved) {
        val requestBuilder     = CopyObjectRequest
          .builder()
          .sourceBucket(sourceBucket)
          .sourceKey(sourceKey)
          .destinationBucket(destinationBucket)
          .destinationKey(destinationKey)
          .checksumAlgorithm(checksumAlgorithm)
        val requestWithOptions = options.newContentType.fold(requestBuilder) { contentType =>
          requestBuilder
            .contentType(contentType.value)
            .metadataDirective(MetadataDirective.REPLACE)
        }
        client.copyObject(requestWithOptions.build()).as(S3OperationResult.Success)
      } else IO.pure(S3OperationResult.AlreadyExists)
    }

  def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult] =
    approveCopy(destinationBucket, destinationKey, options.overwriteTarget).flatMap { approved =>
      if (approved) {
        copyObjectMultiPart(sourceBucket, sourceKey, destinationBucket, destinationKey, options.newContentType).as(
          S3OperationResult.Success
        )
      } else IO.pure(S3OperationResult.AlreadyExists)
    }

  private def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      newContentType: Option[ContentType]
  ): IO[Unit] = {
    val partSize = 5_000_000_000L // 5GB
    for {
      // Initiate the multipart upload
      createMultipartUploadResponse <-
        client.createMultipartUpload(createMultipartUploadRequest(destinationBucket, destinationKey, newContentType))
      // Get the object size
      objectSize                    <- headObject(sourceBucket, sourceKey).map(_.fileSize)
      // Copy the object using 5 MB parts
      completedParts                <- {
        val uploadParts = (0L until objectSize by partSize).zipWithIndex.map { case (start, partNumber) =>
          val lastByte              = Math.min(start + partSize - 1, objectSize - 1)
          val uploadPartCopyRequest = UploadPartCopyRequest.builder
            .sourceBucket(sourceBucket)
            .sourceKey(sourceKey)
            .destinationBucket(destinationBucket)
            .destinationKey(destinationKey)
            .uploadId(createMultipartUploadResponse.uploadId)
            .partNumber(partNumber + 1)
            .copySourceRange(s"bytes=$start-$lastByte")
            .build
          client
            .uploadPartCopy(uploadPartCopyRequest)
            .map(response =>
              CompletedPart.builder
                .partNumber(partNumber + 1)
                .eTag(response.copyPartResult.eTag)
                .checksumSHA256(response.copyPartResult.checksumSHA256)
                .build
            )
        }
        uploadParts.toList.sequence
      }
      // Complete the upload request
      _                             <- logger.info(
                                         s"Completing multipart upload for $destinationBucket/$destinationKey. The parts are: $completedParts"
                                       )
      _                             <- client.completeMultipartUpload(
                                         CompleteMultipartUploadRequest.builder
                                           .bucket(destinationBucket)
                                           .key(destinationKey)
                                           .uploadId(createMultipartUploadResponse.uploadId)
                                           .multipartUpload(CompletedMultipartUpload.builder.parts(completedParts.asJava).build)
                                           .build
                                       )
    } yield ()
  }

  private def approveCopy(destinationBucket: String, destinationKey: String, overwriteEnabled: Boolean) =
    if (overwriteEnabled)
      IO.pure(true)
    else
      objectExists(destinationBucket, destinationKey).map { exists => !exists }

  override def objectExists(bucket: String, key: String): IO[Boolean] = {
    headObject(bucket, key)
      .redeemWith(
        {
          case _: NoSuchKeyException => IO.pure(false)
          case e                     => IO.raiseError(e)
        },
        _ => IO.pure(true)
      )
  }

  override def uploadFile(
      put: PutObjectRequest,
      fileData: FileData
  ): IO[Unit] =
    Stream
      .resource(fileData.toUnicastPublisher)
      .evalMap { publisher =>
        val body = new AsyncRequestBody {
          override def contentLength(): Optional[java.lang.Long]       = Optional.of(put.contentLength)
          override def subscribe(s: Subscriber[? >: ByteBuffer]): Unit = publisher.subscribe(s)
        }
        client.putObject(put.asAws, body)
      }
      .compile
      .drain

  override def updateContentType(bucket: String, key: String, contentType: ContentType): IO[S3OperationResult] =
    headObject(bucket, key).flatMap {
      case head if head.contentType.contains(contentType) => IO.pure(S3OperationResult.AlreadyExists)
      case _                                              =>
        val requestBuilder = CopyObjectRequest
          .builder()
          .sourceBucket(bucket)
          .sourceKey(key)
          .destinationBucket(bucket)
          .destinationKey(key)
          .checksumAlgorithm(checksumAlgorithm)
          .contentType(contentType.value)
          .metadataDirective(MetadataDirective.REPLACE)
        client.copyObject(requestBuilder.build()).as(S3OperationResult.Success)
    }

  override def bucketExists(bucket: String): IO[Boolean] = {
    listObjectsV2(bucket)
      .redeemWith(
        err =>
          err match {
            case _: NoSuchBucketException => IO.pure(false)
            case e                        => IO.raiseError(StorageNotAccessible(e.getMessage))
          },
        _ => IO.pure(true)
      )
  }

  private def getObjectRequest(bucket: String, fileKey: String) = {
    GetObjectRequest
      .builder()
      .bucket(bucket)
      .key(fileKey)
      .build()
  }

  private def createMultipartUploadRequest(bucket: String, fileKey: String, newContentType: Option[ContentType]) = {
    val requestBuilder     =
      CreateMultipartUploadRequest.builder.bucket(bucket).key(fileKey).checksumAlgorithm(checksumAlgorithm)
    val requestWithOptions = newContentType.fold(requestBuilder) { contentType =>
      requestBuilder.contentType(contentType.value)
    }
    requestWithOptions.build
  }

}
