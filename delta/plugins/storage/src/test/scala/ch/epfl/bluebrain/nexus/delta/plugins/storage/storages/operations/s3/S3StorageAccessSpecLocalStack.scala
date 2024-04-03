package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.S3StorageClientImpl
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.testkit.minio.LocalStackS3
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest}

import scala.concurrent.duration.{Duration, DurationInt}

class S3StorageAccessSpecLocalStack
    extends NexusSuite
    with StorageFixtures
    with LocalStackS3.Fixture
    with ActorSystemSetup.Fixture {

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)

  private lazy val s3Client: S3AsyncClientOp[IO]    = localStackS3Client()
  private lazy val s3StorageClient: S3StorageClient = new S3StorageClientImpl(s3Client)
  private lazy val s3Access                         = new S3StorageAccess(s3StorageClient)

  test("List objects in an existing bucket") {
    givenAnS3Bucket { bucket =>
      s3Access.apply(bucket)
    }
  }

  test("Fail to list objects when bucket doesn't exist") {
    s3Access.apply(genString()).intercept[StorageNotAccessible]
  }

  def givenAnS3Bucket(test: String => IO[Unit]): IO[Unit] = {
    val bucket = genString()
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build) >>
      test(bucket) >>
      s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build).void
  }
}
