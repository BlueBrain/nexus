package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{clearDB, expectedImportReport}
import ch.epfl.bluebrain.nexus.ship.S3RunShipSuite.uploadFileToS3
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import eu.timepit.refined.types.string.NonEmptyString
import fs2.aws.s3.models.Models.BucketName
import fs2.io.file.Path
import munit.AnyFixture
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, PutObjectRequest, PutObjectResponse}

import java.nio.file.Paths
import scala.concurrent.duration.Duration

class S3RunShipSuite extends NexusSuite with RunShipSuite.Fixture with LocalStackS3StorageClient.Fixture {

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]]  = List(mainFixture, localStackS3Client)
  private lazy val xas                            = mainFixture()
  private lazy val (s3Client: S3StorageClient, _) = localStackS3Client()

  private val bucket = BucketName(NonEmptyString.unsafeFrom("bucket"))

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    clearDB(xas).accepted
    ()
  }

  test("Run import from S3 providing a single file") {
    val importFilePath = Path("/import/import.json")
    for {
      _ <- uploadFileToS3(s3Client, bucket, importFilePath)
      _ <- RunShip.s3Ship(s3Client, bucket).run(importFilePath, None).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Succeed in overloading the default config with an external config in S3") {
    val configPath = Path("/config/external.conf")
    for {
      _          <- uploadFileToS3(s3Client, bucket, configPath)
      shipConfig <- RunShip.s3Ship(s3Client, bucket).loadConfig(configPath.some)
      _           = assertEquals(shipConfig.baseUri.toString, "https://bbp.epfl.ch/v1")
    } yield ()
  }

  test("Run import from S3 providing a directory") {
    val directoryPath = Path("/import/multi-part-import")
    for {
      _ <- uploadFileToS3(s3Client, bucket, Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.json"))
      _ <-
        uploadFileToS3(s3Client, bucket, Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.success"))
      _ <- uploadFileToS3(s3Client, bucket, Path("/import/multi-part-import/2024-04-06T11:34:31.165389Z.json"))
      _ <- RunShip
             .s3Ship(s3Client, bucket)
             .run(directoryPath, None)
             .assertEquals(expectedImportReport)
    } yield ()
  }

}

object S3RunShipSuite {

  def uploadFileToS3(s3Client: S3StorageClient, bucket: BucketName, path: Path): IO[PutObjectResponse] = {
    s3Client.underlyingClient.createBucket(CreateBucketRequest.builder().bucket(bucket.value.value).build) >>
      s3Client.underlyingClient
        .putObject(
          PutObjectRequest.builder.bucket(bucket.value.value).key(path.toString).build,
          Paths.get(getClass.getResource(path.toString).toURI)
        )
  }

}
