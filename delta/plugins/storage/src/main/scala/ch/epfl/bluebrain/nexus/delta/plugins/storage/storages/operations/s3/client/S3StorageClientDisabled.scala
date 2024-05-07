package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.{HeadObject, UploadMetadata}
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import fs2.Stream
import software.amazon.awssdk.services.s3.model._

private[client] object S3StorageClientDisabled extends S3StorageClient {
  private val disabledErr      = FeatureDisabled("S3 storage is disabled")
  private val raiseDisabledErr = IO.raiseError(disabledErr)

  override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] = raiseDisabledErr

  override def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response] = raiseDisabledErr

  override def readFile(bucket: String, fileKey: String): Stream[IO, Byte] = Stream.raiseError[IO](disabledErr)

  override def headObject(bucket: String, key: String): IO[HeadObject] = raiseDisabledErr

  override def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String
  ): IO[CopyObjectResponse] = raiseDisabledErr

  override def objectExists(bucket: String, key: String): IO[Boolean] = raiseDisabledErr

  override def uploadFile(
      fileData: Stream[IO, Byte],
      bucket: String,
      key: String
  ): IO[UploadMetadata] = raiseDisabledErr

  override def bucketExists(bucket: String): IO[Boolean] = raiseDisabledErr

  override def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String
  ): IO[CompleteMultipartUploadResponse] = raiseDisabledErr

  override def readFileMultipart(bucket: String, fileKey: String): Stream[IO, Byte] = throw disabledErr
}
