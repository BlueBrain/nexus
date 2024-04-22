package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import fs2.Stream
import fs2.aws.s3.S3
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsCredentialsProvider, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{ChecksumMode, HeadObjectRequest, HeadObjectResponse, ListObjectsV2Request, ListObjectsV2Response}

import java.net.URI

trait S3StorageClient {
  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]

  def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response]

  final def readFile(bucket: String, fileKey: String): Stream[IO, Byte] =
    (Stream.eval(parseBucket(bucket)), Stream.eval(parseFileKey(fileKey))).flatMapN(readFile)

  def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte]

  def headObject(bucket: String, key: String): IO[HeadObjectResponse]

  def underlyingClient: S3AsyncClientOp[IO]

  def baseEndpoint: Uri
}

object S3StorageClient {
  def resource(s3Config: Option[S3StorageConfig]): Resource[IO, S3StorageClient] = s3Config match {
    case Some(cfg) =>
      val creds =
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(cfg.defaultAccessKey.value, cfg.defaultSecretKey.value)
        )
      resource(URI.create(cfg.defaultEndpoint.toString()), creds)

    case None => Resource.pure(S3StorageClientDisabled)
  }

  def resource(endpoint: URI, credentialProvider: AwsCredentialsProvider): Resource[IO, S3StorageClient] =
    Interpreter[IO]
      .S3AsyncClientOpResource(
        S3AsyncClient
          .builder()
          .credentialsProvider(credentialProvider)
          .endpointOverride(endpoint)
          .forcePathStyle(true)
          .region(Region.US_EAST_1)
      )
      .map(new S3StorageClientImpl(_, endpoint.toString))

  final class S3StorageClientImpl(client: S3AsyncClientOp[IO], val baseEndpoint: Uri) extends S3StorageClient {
    private val s3: S3[IO] = S3.create(client)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())

    override def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket.value.value).prefix(prefix).build())

    override def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte] =
      s3.readFile(bucket, fileKey)

    override def headObject(bucket: String, key: String): IO[HeadObjectResponse] =
      client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).checksumMode(ChecksumMode.ENABLED).build)

    override def underlyingClient: S3AsyncClientOp[IO] = client
  }

  final case object S3StorageClientDisabled extends S3StorageClient {
    private val disabledErr      = FeatureDisabled("S3 storage is disabled")
    private val raiseDisabledErr = IO.raiseError(disabledErr)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] = raiseDisabledErr

    override def listObjectsV2(bucket: BucketName, prefix: String): IO[ListObjectsV2Response] = raiseDisabledErr

    override def readFile(bucket: BucketName, fileKey: FileKey): Stream[IO, Byte] = Stream.raiseError[IO](disabledErr)

    override def headObject(bucket: String, key: String): IO[HeadObjectResponse] = raiseDisabledErr

    override def underlyingClient: S3AsyncClientOp[IO] = throw disabledErr

    override def baseEndpoint: Uri = throw disabledErr
  }
}
