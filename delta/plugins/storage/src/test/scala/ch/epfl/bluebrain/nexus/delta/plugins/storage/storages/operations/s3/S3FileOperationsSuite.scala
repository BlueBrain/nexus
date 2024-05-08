package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Storage.S3Storage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageRejection.StorageNotAccessible
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageValue.S3StorageValue
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.permissions.{read, write}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, UUIDFFixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.{iriStringContextSyntax, uriSyntax}
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import io.laserdisc.pure.s3.tagless.S3AsyncClientOp
import munit.AnyFixture

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.{Duration, DurationInt}

class S3FileOperationsSuite
    extends NexusSuite
    with StorageFixtures
    with UUIDFFixtures.Random
    with ActorSystemSetup.Fixture
    with LocalStackS3StorageClient.Fixture
    with AkkaSourceHelpers
    with S3Helpers {

  override def munitIOTimeout: Duration = 120.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(localStackS3Client, actorSystem)

  implicit private lazy val (s3StorageClient: S3StorageClient, underlying: S3AsyncClientOp[IO], conf: S3StorageConfig) =
    localStackS3Client()
  implicit private lazy val as: ActorSystem                                                                            = actorSystem()

  private lazy val locationGenerator = new S3LocationGenerator(conf.prefixUri)
  private lazy val fileOps           = S3FileOperations.mk(s3StorageClient, locationGenerator)

  private def makeDigest(content: String): ComputedDigest = {
    val value = Hex.valueOf(DigestAlgorithm.SHA256.digest.digest(content.getBytes(StandardCharsets.UTF_8)))
    ComputedDigest(DigestAlgorithm.SHA256, value)
  }

  private def expectedPath(proj: ProjectRef, filename: String): Uri.Path =
    Uri.Path(s"$proj/files/${randomUuid.toString.takeWhile(_ != '-').mkString("/")}/$filename")

  private def expectedLocation(proj: ProjectRef, filename: String): Uri = conf.prefixUri / expectedPath(proj, filename)

  test("List objects in an existing bucket") {
    givenAnS3Bucket { bucket =>
      fileOps.checkBucketExists(bucket)
    }
  }

  test("Fail to list objects when bucket doesn't exist") {
    fileOps.checkBucketExists(genString()).intercept[StorageNotAccessible]
  }

  test("Save and fetch an object in a bucket") {
    givenAnS3Bucket { bucket =>
      val storageValue = S3StorageValue(
        default = false,
        bucket = bucket,
        readPermission = read,
        writePermission = write,
        maxFileSize = 20
      )

      val iri     = iri"http://localhost/s3"
      val project = ProjectRef.unsafe("org", "project")
      val storage = S3Storage(iri, project, storageValue, Json.obj())

      val filename = "myfile.txt"
      val content  = genString()
      val digest   = makeDigest(content)
      val entity   = HttpEntity(content)

      val location         = expectedLocation(project, filename)
      val expectedMetadata =
        FileStorageMetadata(
          randomUuid,
          content.length.toLong,
          digest,
          FileAttributesOrigin.Client,
          location,
          location.path
        )

      val result = for {
        storageMetadata <- fileOps.save(storage, filename, entity)
        _                = assertEquals(storageMetadata, expectedMetadata)
        source          <- fileOps.fetch(bucket, storageMetadata.path)
      } yield consume(source)

      assertIO(result, content)
    }
  }

  test("register and fetch an existing S3 file") {
    givenAnS3Bucket { bucket =>
      val fileContents = genString()
      givenAFileInABucket(bucket, fileContents) { key =>
        val path = Uri.Path(key)

        val result = for {
          storageMetadata <- fileOps.register(bucket, path)
          _                = assertEquals(storageMetadata.metadata.path, path)
          _                = assertEquals(storageMetadata.metadata.location, Uri(key))
          source          <- fileOps.fetch(bucket, path)
        } yield consume(source)

        assertIO(result, fileContents)
      }
    }
  }
}
