package ch.epfl.bluebrain.nexus.ship

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient.HeadObject
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.ship.ImportReport.Statistics
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{checkFor, expectedImportReport, getDistinctOrgProjects, noopS3Client}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import fs2.io.file.Path
import munit.AnyFixture
import software.amazon.awssdk.services.s3.model.{ChecksumAlgorithm, CompleteMultipartUploadResponse, CopyObjectResponse, ListObjectsV2Response}

import java.time.Instant

class RunShipSuite extends NexusSuite with Doobie.Fixture with ShipConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobieTruncateAfterTest)
  private lazy val xas                           = doobieTruncateAfterTest()

  private def asPath(path: String): IO[Path] = loader.absolutePath(path).map(Path(_))

  private def eventsStream(path: String, offset: Offset = Offset.start) =
    asPath(path).map { path =>
      EventStreamer.localStreamer.stream(path, offset)
    }

  test("Run import by providing the path to a file") {
    for {
      events <- eventsStream("import/import.json")
      _      <- RunShip(events, noopS3Client, inputConfig, xas).assertEquals(expectedImportReport)
      _      <- checkFor("elasticsearch", nxv + "defaultElasticSearchIndex", xas).assertEquals(1)
      _      <- checkFor("blazegraph", nxv + "defaultSparqlIndex", xas).assertEquals(1)
      _      <- checkFor("storage", nxv + "defaultS3Storage", xas).assertEquals(1)
    } yield ()
  }

  test("Run import by providing the path to a directory") {
    for {
      events <- eventsStream("import/multi-part-import")
      _      <- RunShip(events, noopS3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Test the increment") {
    val start = Offset.at(2)
    for {
      events <- eventsStream("import/two-projects.json", offset = start)
      _      <- RunShip(events, noopS3Client, inputConfig, xas).map { report =>
                  assert(report.offset == Offset.at(2L))
                  assert(thereIsOneProjectEventIn(report))
                }
    } yield ()
  }

  test("Import and map public/sscx to obp/somato") {
    val original                 = ProjectRef.unsafe("public", "sscx")
    val target                   = ProjectRef.unsafe("obp", "somato")
    val configWithProjectMapping = inputConfig.copy(
      projectMapping = Map(original -> target)
    )
    for {
      events <- eventsStream("import/import.json")
      _      <- RunShip(events, noopS3Client, configWithProjectMapping, xas)
      _      <- getDistinctOrgProjects(xas).map { project =>
                  assertEquals(project, target)
                }
    } yield ()
  }

  private def thereIsOneProjectEventIn(report: ImportReport) =
    report.progress == Map(Projects.entityType -> Statistics(1L, 0L))

}

object RunShipSuite {

  def getDistinctOrgProjects(xas: Transactors): IO[ProjectRef] =
    sql"""
         | SELECT DISTINCT org, project FROM scoped_events;
       """.stripMargin.query[(Label, Label)].unique.transact(xas.read).map { case (org, proj) =>
      ProjectRef(org, proj)
    }

  def checkFor(entityType: String, id: Iri, xas: Transactors): IO[Int] =
    sql"""
         | SELECT COUNT(*) FROM scoped_events 
         | WHERE type = $entityType
         | AND id = ${id.toString}
       """.stripMargin.query[Int].unique.transact(xas.read)

  private val noopS3Client = new S3StorageClient {
    override def listObjectsV2(bucket: String): IO[ListObjectsV2Response] =
      IO.raiseError(new NotImplementedError("listObjectsV2 is not implemented"))

    override def listObjectsV2(bucket: String, prefix: String): IO[ListObjectsV2Response] =
      IO.raiseError(new NotImplementedError("listObjectsV2 is not implemented"))

    override def readFile(bucket: String, fileKey: String): fs2.Stream[IO, Byte] =
      fs2.Stream.empty

    override def headObject(bucket: String, key: String): IO[HeadObject] =
      IO.raiseError(new NotImplementedError("headObject is not implemented"))

    override def copyObject(
        sourceBucket: String,
        sourceKey: String,
        destinationBucket: String,
        destinationKey: String,
        checksumAlgorithm: ChecksumAlgorithm
    ): IO[CopyObjectResponse] =
      IO.raiseError(new NotImplementedError("copyObject is not implemented"))

    override def baseEndpoint: Uri = Uri.apply("http://localhost:4566")

    override def uploadFile(
        fileData: fs2.Stream[IO, Byte],
        bucket: String,
        key: String,
        algorithm: DigestAlgorithm
    ): IO[S3StorageClient.UploadMetadata] =
      IO.raiseError(new NotImplementedError("uploadFile is not implemented"))

    override def objectExists(bucket: String, key: String): IO[Boolean] =
      IO.raiseError(new NotImplementedError("objectExists is not implemented"))

    override def bucketExists(bucket: String): IO[Boolean] =
      IO.raiseError(new NotImplementedError("bucketExists is not implemented"))

    override def prefix: Uri = throw new NotImplementedError("prefix is not implemented")

    override def copyObjectMultiPart(
        sourceBucket: String,
        sourceKey: String,
        destinationBucket: String,
        destinationKey: String,
        checksumAlgorithm: ChecksumAlgorithm
    ): IO[CompleteMultipartUploadResponse] =
      IO.raiseError(new NotImplementedError("copyObjectMultiPart is not implemented"))

    override def readFileMultipart(bucket: String, fileKey: String): fs2.Stream[IO, Byte] =
      fs2.Stream.empty
  }

  // The expected import report for the import.json file, as well as for the /import/multi-part-import directory
  val expectedImportReport: ImportReport = ImportReport(
    Offset.at(9999999L),
    Instant.parse("2099-12-31T22:59:59.999Z"),
    Map(
      Projects.entityType  -> Statistics(5L, 0L),
      Resolvers.entityType -> Statistics(5L, 0L),
      Resources.entityType -> Statistics(1L, 0L),
      EntityType("xxx")    -> Statistics(0L, 1L)
    )
  )

}
