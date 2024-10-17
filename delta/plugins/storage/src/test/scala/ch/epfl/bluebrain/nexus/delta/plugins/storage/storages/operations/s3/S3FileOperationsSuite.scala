package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileAttributes.FileAttributesOrigin
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileStorageMetadata
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.StoragesConfig.S3StorageConfig
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.AkkaSourceHelpers
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.StorageFileRejection.FetchFileRejection
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.UploadingFile.S3UploadingFile
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.{StorageFixtures, UUIDFFixtures}
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.uriSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.actor.ActorSystemSetup
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
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

  private lazy val locationGenerator = new S3LocationGenerator(conf.prefixPath)
  private lazy val fileOps           = S3FileOperations.mk(s3StorageClient, locationGenerator)

  private def makeDigest(content: String): ComputedDigest = {
    val value = Hex.valueOf(DigestAlgorithm.SHA256.digest.digest(content.getBytes(StandardCharsets.UTF_8)))
    ComputedDigest(DigestAlgorithm.SHA256, value)
  }

  private def expectedPath(proj: ProjectRef, filename: String): Uri.Path =
    Uri.Path(s"$proj/files/${randomUuid.toString.takeWhile(_ != '-').mkString("/")}/$filename")

  private def expectedLocation(proj: ProjectRef, filename: String): Uri =
    Uri.Empty / conf.prefixPath / expectedPath(proj, filename)

  test("Save and fetch an object in a bucket") {
    givenAnS3Bucket { bucket =>
      val project = ProjectRef.unsafe("org", "project")

      val filename      = "myfile.txt"
      val content       = genString()
      val contentType   = ContentTypes.`text/plain(UTF-8)`
      val contentLength = content.length.toLong
      val digest        = makeDigest(content)
      val entity        = HttpEntity(content)
      val uploading     = S3UploadingFile(project, bucket, filename, contentType, contentLength, entity)

      val location         = expectedLocation(project, filename)
      val expectedMetadata =
        FileStorageMetadata(
          randomUuid,
          contentLength,
          digest,
          FileAttributesOrigin.Client,
          location,
          location.path
        )

      for {
        storageMetadata <- fileOps.save(uploading)
        _                = assertEquals(storageMetadata, expectedMetadata)
        headObject      <- s3StorageClient.headObject(bucket, UrlUtils.decode(storageMetadata.path))
        _                = assertEquals(headObject.digest, digest)
        _                = assertEquals(headObject.contentType, Some(contentType))
        _               <- fetchFileContent(bucket, storageMetadata.path).assertEquals(content)
      } yield ()
    }
  }

  test("Fail to fetch a missing file from a bucket") {
    givenAnS3Bucket { bucket =>
      fetchFileContent(bucket, Uri.Path("/xxx/missing-file")).intercept[FetchFileRejection.FileNotFound]
    }
  }

  test("Link and fetch an existing S3 file") {
    givenAnS3Bucket { bucket =>
      val fileContents = genString()
      givenAFileInABucket(bucket, fileContents) { key =>
        val path = Uri.Path(key)

        for {
          storageMetadata <- fileOps.link(bucket, path)
          _                = assertEquals(storageMetadata.metadata.path, path)
          _                = assertEquals(storageMetadata.metadata.location, Uri(key))
          _               <- fetchFileContent(bucket, path).assertEquals(fileContents)
        } yield ()
      }
    }
  }

  private def fetchFileContent(bucket: String, path: Uri.Path) =
    fileOps.fetch(bucket, path).flatMap { source => consumeIO(source) }
}
