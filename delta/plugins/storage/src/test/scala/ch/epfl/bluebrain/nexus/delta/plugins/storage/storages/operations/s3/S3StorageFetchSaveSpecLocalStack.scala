package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StorageFixtures
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.S3StorageClientImpl
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.minio.LocalStackS3
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, DeleteBucketRequest}

import java.util.UUID
import scala.concurrent.duration.{Duration, DurationInt}

class S3StorageFetchSaveSpecLocalStack
    extends NexusSuite
    with StorageFixtures
    with LocalStackS3.Fixture
    with ActorSystemSetup.Fixture {

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client, actorSystem)

  private val uuid                  = UUID.fromString("8049ba90-7cc6-4de5-93a1-802c04200dcc")
  implicit private val uuidf: UUIDF = UUIDF.fixed(uuid)

  private lazy val s3Client: S3AsyncClientOp[IO]    = localStackS3Client()
  implicit private lazy val as: ActorSystem         = actorSystem()
  private lazy val s3StorageClient: S3StorageClient = new S3StorageClientImpl(s3Client)

  test("Save and fetch an object in a bucket") {
    println(s"Did we get here?")
    givenAnS3Bucket { bucket =>
      val s3Fetch      = new S3StorageFetchFile(s3StorageClient, bucket)
      val storageValue = S3StorageValue(
        default = false,
        algorithm = DigestAlgorithm.default,
        bucket = bucket,
        endpoint = None,
        region = None,
        readPermission = read,
        writePermission = write,
        maxFileSize = 20
      )
      val iri          = iri"http://localhost/s3"
      val project      = ProjectRef.unsafe("org", "project")
      val storage      = S3Storage(iri, project, storageValue, Json.obj())
      val s3Save       = new S3StorageSaveFile2(s3Client, storage)

      val filename = "myfile.txt"
      val content  = "file content"
      val entity   = HttpEntity(content)

      IO.println(s"Saving file") >>
        s3Save.apply(filename, entity).flatMap { attr =>
          IO.println(s"Saved file attributes: $attr") >>
            // TODO check returned file
            s3Fetch.apply(attr.path) >>
            IO.println(s"Fetched file")
        }
    }
  }

  def givenAnS3Bucket(test: String => IO[Unit]): IO[Unit] = {
    val bucket = genString()
    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build) >>
      test(bucket) >>
      s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build).void
  }
}
