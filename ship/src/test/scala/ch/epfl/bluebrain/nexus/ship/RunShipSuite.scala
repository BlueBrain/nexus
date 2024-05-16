package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.DigestAlgorithm
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateGet
import ch.epfl.bluebrain.nexus.ship.ImportReport.Statistics
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{checkFor, expectedImportReport, fetchFileAttributes, getDistinctOrgProjects}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.Get
import doobie.implicits._
import fs2.Stream
import fs2.io.file.Path
import io.circe.optics.JsonPath.root
import munit.{AnyFixture, Location}

import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.jdk.CollectionConverters._

class RunShipSuite
    extends NexusSuite
    with Doobie.Fixture
    with LocalStackS3StorageClient.Fixture
    with ShipConfigFixtures {

  override def munitFixtures: Seq[AnyFixture[_]] = List(doobieTruncateAfterTest, localStackS3Client)
  private lazy val xas                           = doobieTruncateAfterTest()
  private lazy val (s3Client, underlying, _)     = localStackS3Client()

  private val importBucket = inputConfig.files.importBucket
  private val targetBucket = inputConfig.files.targetBucket

  private val fileContent   = "File content"
  private val contentLength = fileContent.length.toLong
  private val fileDigest    =
    ComputedDigest(DigestAlgorithm.SHA256, Hex.valueOf(DigestAlgorithm.SHA256.digest.digest(fileContent.getBytes)))

  private def asPath(path: String): IO[Path] = loader.absolutePath(path).map(Path(_))

  private def eventsStream(path: String, offset: Offset = Offset.start) =
    asPath(path).map { path =>
      Stream.eval(LocalStackS3StorageClient.createBucket(underlying, importBucket)) >>
        Stream.eval(LocalStackS3StorageClient.createBucket(underlying, targetBucket)) >>
        EventStreamer.localStreamer.stream(path, offset).evalTap { row =>
          IO.whenA(row.`type` == Files.entityType) {
            root.attributes.path.string
              .getOption(row.value)
              .traverse { path =>
                val decodedPath     = UrlUtils.decode(path)
                val contentAsBuffer = StandardCharsets.UTF_8.encode(fileContent).asReadOnlyBuffer()
                s3Client.uploadFile(Stream.emit(contentAsBuffer), importBucket, decodedPath, contentLength)
              }
              .void
          }
        }
    }

  test("Run import by providing the path to a file") {
    for {
      events <- eventsStream("import/import.json")
      _      <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
      _      <- checkFor("elasticsearch", nxv + "defaultElasticSearchIndex", xas).assertEquals(1)
      _      <- checkFor("blazegraph", nxv + "defaultSparqlIndex", xas).assertEquals(1)
      _      <- checkFor("storage", nxv + "defaultS3Storage", xas).assertEquals(1)
    } yield ()
  }

  test("Run import by providing the path to a directory") {
    for {
      events <- eventsStream("import/multi-part-import")
      _      <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Test the increment") {
    val start = Offset.at(2)
    for {
      events <- eventsStream("import/two-projects.json", offset = start)
      _      <- RunShip(events, s3Client, inputConfig, xas).map { report =>
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
      _      <- RunShip(events, s3Client, configWithProjectMapping, xas)
      _      <- getDistinctOrgProjects(xas).map { project =>
                  assertEquals(project, target)
                }
    } yield ()
  }

  private def thereIsOneProjectEventIn(report: ImportReport) =
    report.progress == Map(Projects.entityType -> Statistics(1L, 0L))

  test("Import files in S3 and in the primary store") {
    for {
      events               <- eventsStream("import/file-import.json")
      _                    <- RunShip(events, s3Client, inputConfig, xas)
      project               = ProjectRef.unsafe("public", "sscx")
      // File with an old path to be rewritten
      oldPathFileId         = iri"https://bbp.epfl.ch/neurosciencegraph/data/old-path"
      oldPathLocationRev1   = "/prefix/public/sscx/files/0/a/7/9/a/d/1/d/002_160120B3_OH.nwb"
      oldPathLocationRev2   = "/prefix/public/sscx/files/8/9/5/4/c/3/e/c/002_160120B3_OH_updated.nwb"
      _                    <- checkFor("file", oldPathFileId, xas).assertEquals(3)
      _                    <- assertS3Object(oldPathLocationRev1)
      _                    <- assertS3Object(oldPathLocationRev2)
      _                    <- assertFileAttributes(project, oldPathFileId)(oldPathLocationRev2, "002_160120B3_OH_updated.nwb")
      // File with a blank filename
      blankFilenameId       = iri"https://bbp.epfl.ch/neurosciencegraph/data/empty-filename"
      blankFilenameLocation = "/prefix/public/sscx/files/2/b/3/9/7/9/3/0/file"
      _                    <- checkFor("file", blankFilenameId, xas).assertEquals(1)
      _                    <- assertS3Object(blankFilenameLocation)
      _                    <- assertFileAttributes(project, blankFilenameId)(blankFilenameLocation, "file")
      // File with special characters in the filename
      specialCharsId        = iri"https://bbp.epfl.ch/neurosciencegraph/data/special-chars-filename"
      specialCharsLocation  = "/prefix/public/sscx/files/1/2/3/4/5/6/7/8/special [file].json"
      _                    <- checkFor("file", specialCharsId, xas).assertEquals(1)
      _                    <- assertS3Object(specialCharsLocation)
      _                    <- assertFileAttributes(project, specialCharsId)(specialCharsLocation, "special [file].json")
    } yield ()
  }

  private def assertFileAttributes(project: ProjectRef, id: Iri)(expectedLocation: String, expectedFileName: String) =
    fetchFileAttributes(project, id, xas).map { attributes =>
      assertEquals(UrlUtils.decode(attributes.location), expectedLocation)
      assertEquals(UrlUtils.decode(attributes.path), expectedLocation)
      assertEquals(attributes.filename, expectedFileName)
    }

  private def assertS3Object(key: String)(implicit location: Location): IO[Unit] =
    s3Client
      .headObject(targetBucket, key)
      .map { head =>
        assertEquals(head.fileSize, contentLength)
        assertEquals(head.digest, fileDigest)
      }
      .recoverWith { e =>
        s3Client.listObjectsV2(targetBucket).map { listResponse =>
          val keys    = listResponse.contents().asScala.map(_.key())
          val message = s"$key could not be found in target bucket. Did you mean: ${keys.mkString("'", "','", "'")} ?"
          fail(message, e)
        }
      }

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
         | AND id = $id
       """.stripMargin.query[Int].unique.transact(xas.read)

  def fetchFileAttributes(project: ProjectRef, id: Iri, xas: Transactors): IO[FileAttributes] = {
    implicit val getValue: Get[FileState] = FileState.serializer.getValue
    ScopedStateGet
      .latest[Iri, FileState](Files.entityType, project, id)
      .transact(xas.read)
      .flatMap { stateOpt =>
        IO.fromOption(stateOpt.map(_.attributes))(FileNotFound(id, project))
      }
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
