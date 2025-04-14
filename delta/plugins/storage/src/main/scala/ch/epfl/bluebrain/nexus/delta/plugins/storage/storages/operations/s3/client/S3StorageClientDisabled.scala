package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.ContentType
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, HeadObject, PutObjectRequest, S3OperationResult}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import fs2.Stream
import software.amazon.awssdk.services.s3.model.*

import java.nio.ByteBuffer

private[client] object S3StorageClientDisabled extends S3StorageClient {
  private val disabledErr      = FeatureDisabled("S3 storage is disabled")
  private val raiseDisabledErr = IO.raiseError(disabledErr)

  override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] = raiseDisabledErr

  override def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response] = raiseDisabledErr

  override def readFile(bucket: String, fileKey: String): Stream[IO, ByteBuffer] = Stream.raiseError[IO](disabledErr)

  override def headObject(bucket: String, key: String): IO[HeadObject] = raiseDisabledErr

  override def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult] = raiseDisabledErr

  override def objectExists(bucket: String, key: String): IO[Boolean] = raiseDisabledErr

  override def uploadFile(
      putObjectRequest: PutObjectRequest,
      data: Stream[IO, ByteBuffer]
  ): IO[Unit] = raiseDisabledErr

  override def updateContentType(bucket: String, key: String, contentType: ContentType): IO[S3OperationResult] =
    raiseDisabledErr

  override def bucketExists(bucket: String): IO[Boolean] = raiseDisabledErr

  override def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult] = raiseDisabledErr

  override def readFileMultipart(bucket: String, fileKey: String): Stream[IO, Byte] = throw disabledErr
}
