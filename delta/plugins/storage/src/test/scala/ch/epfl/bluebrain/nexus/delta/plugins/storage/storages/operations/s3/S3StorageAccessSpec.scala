package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.S3Helpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

import scala.concurrent.duration.{Duration, DurationInt}

class S3StorageAccessSpec
    extends NexusSuite
    with StorageFixtures
    with LocalStackS3StorageClient.Fixture
    with ActorSystemSetup.Fixture
    with S3Helpers {

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client)

  implicit private lazy val (s3Client: S3StorageClient, underlying: S3AsyncClientOp[IO], _) = localStackS3Client()
  private lazy val s3Access                                                                 = new S3StorageAccess(s3Client)

  test("List objects in an existing bucket") {
    givenAnS3Bucket { bucket =>
      s3Access.apply(bucket)
    }
  }

  test("Fail to list objects when bucket doesn't exist") {
    s3Access.apply(genString()).intercept[StorageNotAccessible]
  }
}
