package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Ref}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.{HeadObject, UploadMetadata}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClientImpl._
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import fs2.interop.reactivestreams.PublisherOps
import fs2.{Chunk, Pipe, Stream}
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import org.apache.commons.codec.binary.Hex
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.s3.model._

import java.util.Base64
import scala.jdk.CollectionConverters._

final private[client] class S3StorageClientImpl(client: S3AsyncClientOp[IO], val baseEndpoint: Uri, val prefix: Uri)
    extends S3StorageClient {

  override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
    client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())

  override def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response] =
    client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())

  override def readFile(bucket: String, fileKey: String): Stream[IO, Byte] = {
    Stream
      .eval(client.getObject(getObjectRequest(bucket, fileKey), new Fs2StreamAsyncResponseTransformer))
      .flatMap(_.toStreamBuffered[IO](2).flatMap(bb => Stream.chunk(Chunk.byteBuffer(bb))))
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
      .map(resp =>
        HeadObject(
          resp.contentLength(),
          Option(resp.contentType()),
          Option(resp.checksumSHA256()),
          Option(resp.checksumSHA1())
        )
      )

  override def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      checksumAlgorithm: ChecksumAlgorithm
  ): IO[CopyObjectResponse] =
    client.copyObject(
      CopyObjectRequest
        .builder()
        .sourceBucket(sourceBucket)
        .sourceKey(sourceKey)
        .destinationBucket(destinationBucket)
        .destinationKey(destinationKey)
        .checksumAlgorithm(checksumAlgorithm)
        .build()
    )

  def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String
  ): IO[CompleteMultipartUploadResponse] = {
    val partSize = 5_000_000_000L // 5GB
    for {
      // Initiate the multipart upload
      createMultipartUploadResponse   <-
        client.createMultipartUpload(createMultipartUploadRequest(destinationBucket, destinationKey))
      // Get the object size
      objectSize                      <- headObject(sourceBucket, sourceKey).map(_.fileSize)
      // Copy the object using 5 MB parts
      completedParts                  <- {
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
                .build
            )
        }
        uploadParts.toList.sequence
      }
      // Complete the upload request
      completeMultipartUploadResponse <-
        client.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder
            .bucket(destinationBucket)
            .key(destinationKey)
            .uploadId(createMultipartUploadResponse.uploadId)
            .multipartUpload(CompletedMultipartUpload.builder.parts(completedParts.asJava).build)
            .build
        )
    } yield completeMultipartUploadResponse
  }

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
      fileData: Stream[IO, Byte],
      bucket: String,
      key: String,
      algorithm: DigestAlgorithm
  ): IO[UploadMetadata] = {
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
      location     = prefix / Uri.Path(key)
    } yield UploadMetadata(digest, fileSize, location)
  }

  private def uploadFilePipe(bucket: String, key: String, algorithm: DigestAlgorithm): Pipe[IO, Byte, String] = { in =>
    fs2.Stream.eval {
      in.compile.to(Chunk).flatMap { chunks =>
        val bs = chunks.toByteBuffer
        for {
          response <- client.putObject(
                        PutObjectRequest
                          .builder()
                          .bucket(bucket)
                          .deltaDigest(algorithm)
                          .key(key)
                          .build(),
                        AsyncRequestBody.fromByteBuffer(bs)
                      )
        } yield {
          checksumFromResponse(response, algorithm)
        }
      }
    }
  }

  private def checksumFromResponse(response: PutObjectResponse, algorithm: DigestAlgorithm): String = {
    algorithm.value match {
      case "SHA-256" => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA256()))
      case "SHA-1"   => Hex.encodeHexString(Base64.getDecoder.decode(response.checksumSHA1()))
      case _         => throw new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}")
    }
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

  private def createMultipartUploadRequest(bucket: String, fileKey: String) =
    CreateMultipartUploadRequest.builder
      .bucket(bucket)
      .key(fileKey)
      .build

}

private object S3StorageClientImpl {
  implicit class PutObjectRequestOps(request: PutObjectRequest.Builder) {
    def deltaDigest(algorithm: DigestAlgorithm): PutObjectRequest.Builder =
      algorithm.value match {
        case "SHA-256" => request.checksumAlgorithm(ChecksumAlgorithm.SHA256)
        case "SHA-1"   => request.checksumAlgorithm(ChecksumAlgorithm.SHA1)
        case _         => throw new IllegalArgumentException(s"Unsupported algorithm for S3: ${algorithm.value}")
      }
  }
}
