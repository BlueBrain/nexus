package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.ContentType
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{CopyOptions, HeadObject, PutObjectRequest, S3OperationResult}
import fs2.Stream
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, DefaultCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.core.checksums.ChecksumValidation
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.*

import java.net.URI
import java.nio.ByteBuffer

trait S3StorageClient {

  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]

  def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response]

  def readFile(bucket: String, fileKey: String): Stream[IO, ByteBuffer]

  def readFileMultipart(bucket: String, fileKey: String): Stream[IO, Byte]

  def headObject(bucket: String, key: String): IO[HeadObject]

  def copyObject(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult]

  def copyObjectMultiPart(
      sourceBucket: String,
      sourceKey: String,
      destinationBucket: String,
      destinationKey: String,
      options: CopyOptions
  ): IO[S3OperationResult]

  def uploadFile(
      put: PutObjectRequest,
      fileData: Stream[IO, ByteBuffer]
  ): IO[Unit]

  def updateContentType(bucket: String, key: String, contentType: ContentType): IO[S3OperationResult]

  def objectExists(bucket: String, key: String): IO[Boolean]
  def bucketExists(bucket: String): IO[Boolean]
}

object S3StorageClient {

  def resource(s3Config: Option[S3StorageConfig]): Resource[IO, S3StorageClient] = s3Config match {
    case Some(cfg) =>
      val creds =
        if (cfg.useDefaultCredentialProvider) DefaultCredentialsProvider.create()
        else {
          StaticCredentialsProvider.create(
            AwsBasicCredentials.create(cfg.defaultAccessKey.value, cfg.defaultSecretKey.value)
          )
        }
      resource(URI.create(cfg.defaultEndpoint.toString()), creds)

    case None => Resource.pure(S3StorageClientDisabled)
  }

  def resource(endpoint: URI, credentialProvider: AwsCredentialsProvider): Resource[IO, S3StorageClient] = {
    val overrideConfigurationBuilder =
      ClientOverrideConfiguration
        .builder()
        // Disable checksum for streaming operations
        .putExecutionAttribute(SdkExecutionAttribute.HTTP_RESPONSE_CHECKSUM_VALIDATION, ChecksumValidation.FORCE_SKIP)
    Interpreter[IO]
      .S3AsyncClientOpResource(
        S3AsyncClient
          .builder()
          .credentialsProvider(credentialProvider)
          .endpointOverride(endpoint)
          .overrideConfiguration(overrideConfigurationBuilder.build())
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
      )
      .map(new S3StorageClientImpl(_))
  }

  def unsafe(client: S3AsyncClientOp[IO]): S3StorageClient =
    new S3StorageClientImpl(client)

  def disabled: S3StorageClient = S3StorageClientDisabled
}
