package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.access

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{LocalStackS3StorageClient, S3Helpers}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

class S3StorageAccessSuite extends NexusSuite with LocalStackS3StorageClient.Fixture with S3Helpers {

  override def munitFixtures: Seq[AnyFixture[?]] = List(localStackS3Client)

  implicit private lazy val (s3Client: S3StorageClient, underlying: S3AsyncClientOp[IO], _: S3StorageConfig) =
    localStackS3Client()

  private lazy val s3Access = S3StorageAccess(s3Client)

  test("Succeed for an existing bucket") {
    givenAnS3Bucket { bucket =>
      s3Access.checkBucketExists(bucket)
    }
  }

  test("Fail when a bucket doesn't exist") {
    s3Access.checkBucketExists(genString()).intercept[StorageNotAccessible]
  }
}
