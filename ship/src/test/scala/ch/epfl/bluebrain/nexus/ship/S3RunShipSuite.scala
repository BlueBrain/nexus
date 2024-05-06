package ch.epfl.bluebrain.nexus.ship

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient.{createBucket, uploadFileToS3}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.expectedImportReport
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.io.file.Path
import munit.AnyFixture
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest

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
    val importFilePath = Path("/import/import.json")
    for {
      _     <- uploadFileToS3(fs2S3client, bucket, importFilePath)
      events = EventStreamer.s3eventStreamer(s3Client, bucket).stream(importFilePath, Offset.start)
      _     <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Run import with file events") {
    val importFilePath = Path("/import/file-events-import.json")
    val gif            = Path("gpfs/cat_scream.gif")

    val importBucket = "nexus-ship-production"
    val targetBucket = "nexus-delta-production"
    val shipConfig   = inputConfig.copy(importBucket = importBucket, targetBucket = targetBucket)

    {
      for {
        _     <- uploadFileToS3(fs2S3client, importBucket, importFilePath)
        _     <- uploadFileToS3(fs2S3client, importBucket, gif)
        _     <- createBucket(fs2S3client, targetBucket)
        events = EventStreamer.s3eventStreamer(s3Client, importBucket).stream(importFilePath, Offset.start)
        _     <- RunShip(events, s3Client, shipConfig, xas).map(_.progress(EntityType("file")).success == 1L)
        _     <- fs2S3client.getObjectAttributes(
                   GetObjectAttributesRequest
                     .builder()
                     .bucket(targetBucket)
                     .key(gif.toString)
                     .objectAttributesWithStrings(java.util.List.of("Checksum"))
                     .build()
                 )
      } yield ()
    }.accepted

    println(123)
  }

  test("Run import from S3 providing a directory") {
    val directoryPath = Path("/import/multi-part-import")
    for {
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.json"))
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.success"))
      _     <- uploadFileToS3(fs2S3client, bucket, Path("/import/multi-part-import/2024-04-06T11:34:31.165389Z.json"))
      events = EventStreamer.s3eventStreamer(s3Client, bucket).stream(directoryPath, Offset.start)
      _     <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

}
