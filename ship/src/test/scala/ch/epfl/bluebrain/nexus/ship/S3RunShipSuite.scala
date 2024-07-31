package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient.uploadFileToS3
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.expectedImportReport
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.Path
import munit.AnyFixture

import scala.concurrent.duration.{Duration, DurationInt}

class S3RunShipSuite
    extends NexusSuite
    with Doobie.Fixture
    with LocalStackS3StorageClient.Fixture
    with ShipConfigFixtures {
  private val bucket = "bucket"

  override def munitIOTimeout: Duration = 60.seconds

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobieTruncateAfterTest, localStackS3Client)
  private lazy val xas                           = doobieTruncateAfterTest()
  private lazy val (s3Client, fs2S3client, _)    = localStackS3Client()

  test("Run import from S3 providing a single file") {
    val importFilePath = Path("/import/single/00263821.json")
    for {
      _     <- uploadFileToS3(fs2S3client, bucket, importFilePath)
      events = EventStreamer.s3eventStreamer(s3Client, bucket).stream(importFilePath, Offset.start)
      _     <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Run import from S3 providing a directory") {
    val directoryPath = Path("/import/multi-part-import")
    for {
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/002163821.json"))
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/002163821.success"))
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/04900000.json"))
      events = EventStreamer.s3eventStreamer(s3Client, bucket).stream(directoryPath, Offset.start)
      _     <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

}
