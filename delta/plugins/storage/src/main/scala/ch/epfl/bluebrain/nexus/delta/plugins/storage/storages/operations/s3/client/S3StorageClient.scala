package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client

import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FeatureDisabled
import io.laserdisc.pure.s3.tagless.{Interpreter, S3AsyncClientOp}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.{ListObjectsV2Request, ListObjectsV2Response}

import java.net.URI

trait S3StorageClient {
  def listObjectsV2(bucket: String): IO[ListObjectsV2Response]
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
            // TODO does this need to be changed on the fly?
            .endpointOverride(URI.create(cfg.defaultEndpoint.toString()))
            .forcePathStyle(true)
            // TODO may want to configure this?
            .region(Region.US_EAST_1)
        )
        .map(new S3StorageClientImpl(_))

    case None => Resource.pure(S3StorageClientDisabled)
  }

  final class S3StorageClientImpl(client: S3AsyncClientOp[IO]) extends S3StorageClient {
    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      client.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
  }

  final case object S3StorageClientDisabled extends S3StorageClient {
    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      IO.raiseError(FeatureDisabled("S3 storage is disabled"))
  }
}
