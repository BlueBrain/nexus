package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.http.scaladsl.model.Uri
import cats.effect.{IO, Resource}
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions
import ch.epfl.bluebrain.nexus.testkit.Generators
import ch.epfl.bluebrain.nexus.testkit.localstack.LocalStackS3
import fs2.io.file.Path
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.CatsEffectSuite
import munit.catseffect.IOFixture
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, PutObjectRequest, PutObjectResponse}

import java.nio.file.Paths

object LocalStackS3StorageClient {
  val ServiceType = Service.S3

  def createBucket(s3Client: S3AsyncClientOp[IO], bucket: String) =
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build)

  def uploadFileToS3(s3Client: S3AsyncClientOp[IO], bucket: String, path: Path): IO[PutObjectResponse] = {
    val absoluteResourcePath = if (path.isAbsolute) path else Path("/" + path.toString)
    createBucket(s3Client, bucket) >>
      s3Client.putObject(
        PutObjectRequest.builder
          .bucket(bucket)
          .key(path.toString)
          .build,
        Paths.get(getClass.getResource(absoluteResourcePath.toString).toURI)
      )
  }

  def s3StorageClientResource(
      defaultBucket: String,
      prefix: String
  ): Resource[IO, (S3StorageClient, S3AsyncClientOp[IO], S3StorageConfig)] =
    LocalStackS3.localstackS3().flatMap { localstack =>
      LocalStackS3.fs2ClientFromLocalstack(localstack).map { client =>
        val creds                  = localstack.staticCredentialsProvider.resolveCredentials()
        val (accessKey, secretKey) = (creds.accessKeyId(), creds.secretAccessKey())
        val conf: S3StorageConfig  = S3StorageConfig(
          defaultEndpoint = Uri(localstack.endpointOverride(LocalStackS3.ServiceType).toString),
          useDefaultCredentialProvider = false,
          defaultAccessKey = Secret(accessKey),
          defaultSecretKey = Secret(secretKey),
          defaultReadPermission = permissions.read,
          defaultWritePermission = permissions.write,
          showLocation = false,
          defaultMaxFileSize = 1,
          defaultBucket = defaultBucket,
          prefix = Some(prefix)
        )
        (S3StorageClient.unsafe(client), client, conf)
      }
    }

  trait Fixture { self: CatsEffectSuite with Generators =>
    val localStackS3Client: IOFixture[(S3StorageClient, S3AsyncClientOp[IO], S3StorageConfig)] =
      ResourceSuiteLocalFixture(
        "s3storageclient",
        s3StorageClientResource(defaultBucket = genString(), prefix = genString())
      )
  }
}
