package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.AnyFixture
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest}

import scala.concurrent.duration.{Duration, DurationInt}

class S3StorageAccessSpec
    extends NexusSuite
    with StorageFixtures
    with LocalStackS3StorageClient.Fixture
    with ActorSystemSetup.Fixture {

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)

  private lazy val (s3Client: S3StorageClient, _) = localStackS3Client()
  private lazy val s3Access                       = new S3StorageAccess(s3Client)

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
    s3Client.underlyingClient.createBucket(CreateBucketRequest.builder().bucket(bucket).build) >>
      test(bucket) >>
      s3Client.underlyingClient.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build).void
  }
}
