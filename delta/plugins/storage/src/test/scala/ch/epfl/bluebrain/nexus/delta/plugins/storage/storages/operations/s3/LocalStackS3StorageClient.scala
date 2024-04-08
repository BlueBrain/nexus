package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions
import ch.epfl.bluebrain.nexus.testkit.minio.LocalStackS3
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.testcontainers.containers.localstack.LocalStackContainer.Service

object LocalStackS3StorageClient {
  val ServiceType = Service.S3

  def s3StorageClientResource(): Resource[IO, (S3StorageClient, S3StorageConfig)] =
    LocalStackS3.localstackS3().flatMap { localstack =>
      LocalStackS3.fs2ClientFromLocalstack(localstack).map { client =>
        val creds                  = localstack.staticCredentialsProvider.resolveCredentials()
        val (accessKey, secretKey) = (creds.accessKeyId(), creds.secretAccessKey())
        val conf: S3StorageConfig  = S3StorageConfig(
          digestAlgorithm = DigestAlgorithm.default,
          defaultEndpoint = Uri(localstack.endpointOverride(LocalStackS3.ServiceType).toString),
          defaultAccessKey = Secret(accessKey),
          defaultSecretKey = Secret(secretKey),
          defaultReadPermission = permissions.read,
          defaultWritePermission = permissions.write,
          showLocation = false,
          defaultMaxFileSize = 1
        )
        (new S3StorageClient.S3StorageClientImpl(client, conf.defaultEndpoint), conf)
      }
    }

  trait Fixture { self: CatsEffectSuite =>
    val localStackS3Client: IOFixture[(S3StorageClient, S3StorageConfig)] =
      ResourceSuiteLocalFixture("s3storageclient", s3StorageClientResource())
  }
}
