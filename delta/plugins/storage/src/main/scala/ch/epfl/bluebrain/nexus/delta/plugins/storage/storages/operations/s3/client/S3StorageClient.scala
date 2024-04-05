package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import cats.effect.{IO, Resource}
import cats.implicits.toBifunctorOps
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import fs2.aws.s3.S3
import fs2.aws.s3.models.Models.{BucketName, FileKey}
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{ListObjectsV2Request, ListObjectsV2Response}

import java.net.URI

trait S3StorageClient {
  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]

  def readFile(bucket: String, fileKey: String): fs2.Stream[IO, Byte]

  def underlyingClient: S3AsyncClientOp[IO]
}

object S3StorageClient {
  def resource(s3Config: Option[S3StorageConfig]): Resource[IO, S3StorageClient] = s3Config match {
    case Some(cfg) =>
      val creds =
        StaticCredentialsProvider.create(
          AwsBasicCredentials.create(cfg.defaultAccessKey.value, cfg.defaultSecretKey.value)
        )

      Interpreter[IO]
        .S3AsyncClientOpResource(
          S3AsyncClient
            .builder()
            .credentialsProvider(creds)
            .endpointOverride(URI.create(cfg.defaultEndpoint.toString()))
            .forcePathStyle(true)
            .region(Region.US_EAST_1)
        )
        .map(new S3StorageClientImpl(_))

    case None => Resource.pure(S3StorageClientDisabled)
  }

  final class S3StorageClientImpl(client: S3AsyncClientOp[IO]) extends S3StorageClient {
    private val s3: S3[IO] = S3.create(client)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())

    def readFile(bucket: String, fileKey: String): fs2.Stream[IO, Byte] =
      for {
        bk    <- fs2.Stream.fromEither[IO].apply(refineV[NonEmpty](bucket).leftMap(e => new IllegalArgumentException(e)))
        fk    <- fs2.Stream.fromEither[IO].apply(refineV[NonEmpty](fileKey).leftMap(e => new IllegalArgumentException(e)))
        bytes <- s3.readFile(BucketName(bk), FileKey(fk))
      } yield bytes

    override def underlyingClient: S3AsyncClientOp[IO] = client
  }

  final case object S3StorageClientDisabled extends S3StorageClient {
    private val disabledErr      = FeatureDisabled("S3 storage is disabled")
    private val raiseDisabledErr = IO.raiseError(disabledErr)

    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] = raiseDisabledErr

    override def readFile(bucket: String, fileKey: String): fs2.Stream[IO, Byte] =
      fs2.Stream.raiseError[IO](disabledErr)

    override def underlyingClient: S3AsyncClientOp[IO] = throw disabledErr
  }
}
