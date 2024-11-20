package ch.epfl.bluebrain.nexus.ship

import akka.http.scaladsl.model.{ContentType, ContentTypes, MediaTypes, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.Hex
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.Digest.ComputedDigest
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileRejection.FileNotFound
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.{FileAttributes, FileState}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.StorageType.DiskStorage
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.{DigestAlgorithm, StorageType}
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.{LocalStackS3StorageClient, PutObjectRequest}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.delta.sourcing.state.ScopedStateGet
import ch.epfl.bluebrain.nexus.ship.ImportReport.Statistics
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{checkFor, expectedImportReport, fetchFileAttributes, getDistinctOrgProjects}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.ship.files.FileCopier.localDiskPath
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.Get
import doobie.syntax.all._
import fs2.Stream
import fs2.io.file.Path
import io.circe.Json
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

  private def asPath(path: String): IO[Path] = loader.absoluteFs2Path(path)

  private def uploadFile(path: String) = {
    val contentAsBuffer = StandardCharsets.UTF_8.encode(fileContent).asReadOnlyBuffer()
    val put             = PutObjectRequest(importBucket, path, Some(ContentTypes.`application/octet-stream`), contentLength)
    s3Client.uploadFile(put, Stream.emit(contentAsBuffer))
  }

  private def decodedFilePath(json: Json) = {
    root.storageType.as[StorageType].getOption(json).flatMap {
      case DiskStorage =>
        root.attributes.path.as[Uri.Path].getOption(json).map { path =>
          localDiskPath(path).toString
        }
      case _           =>
        root.attributes.path.string.getOption(json).map(UrlUtils.decode)
    }

  }

  private def fileContentType = root.attributes.mediaType.string.getOption(_)

  private def generatePhysicalFile(row: RowEvent) =
    IO.whenA(row.`type` == Files.entityType) {
      (decodedFilePath(row.value), fileContentType(row.value)) match {
        case (Some(path), Some(contentType)) if contentType == "application/x-directory" =>
          uploadFile(s"$path/config.txt") // Generate a folder with a config file
        case (Some(path), _) =>
          // Generate a file
          uploadFile(path)
        case _               => IO.unit
      }
    }

  private def eventsStream(path: String, offset: Offset = Offset.start) =
    asPath(path).map { path =>
      Stream.eval(LocalStackS3StorageClient.createBucket(underlying, importBucket)) >>
        Stream.eval(LocalStackS3StorageClient.createBucket(underlying, targetBucket)) >>
        EventStreamer.localStreamer.stream(path, offset).evalTap { row => generatePhysicalFile(row) }
    }

  test("Run import by providing the path to a file") {
    for {
      events <- eventsStream("import/single/00263821.json")
      _      <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
      _      <- checkFor("elasticsearch", nxv + "defaultElasticSearchIndex", xas).assertEquals(1)
      _      <- checkFor("blazegraph", nxv + "defaultSparqlIndex", xas).assertEquals(1)
      _      <- checkFor("compositeviews", nxv + "searchView", xas).assertEquals(1)
      _      <- checkFor("storage", nxv + "defaultS3Storage", xas).assertEquals(1)
    } yield ()
  }

  test("Run import by providing the path to a directory") {
    for {
      events <- eventsStream("import/multi-part-import")
      _      <- RunShip(events, s3Client, inputConfig, xas).assertEquals(expectedImportReport)
      _      <- new DroppedEventStore(xas).count.assertEquals(1L)
    } yield ()
  }

  test("Test the increment") {
    val start = Offset.at(2)
    for {
      events <- eventsStream("import/two-projects/000000001.json", offset = start)
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
      events <- eventsStream("import/single/00263821.json")
      _      <- RunShip(events, s3Client, configWithProjectMapping, xas)
      _      <- getDistinctOrgProjects(xas).map { project =>
                  assertEquals(project, target)
                }
    } yield ()
  }

  private def thereIsOneProjectEventIn(report: ImportReport) =
    report.progress == Map(Projects.entityType -> Statistics(1L, 0L))

  test("Import files in S3 and in the primary store") {
    val textPlain              = MediaTypes.`text/plain`.withMissingCharset
    val applicationOctetStream = MediaTypes.`application/octet-stream`
    for {
      events               <- eventsStream("import/file-import/000000001.json")
      report               <- RunShip(events, s3Client, inputConfig, xas)
      project               = ProjectRef.unsafe("public", "sscx")
      // File with an old path to be rewritten
      oldPathFileId         = iri"https://bbp.epfl.ch/neurosciencegraph/data/old-path"
      oldPathLocationRev1   = "/prefix/public/sscx/files/0/a/7/9/a/d/1/d/002_160120B3_OH.nwb"
      oldPathLocationRev2   = "/prefix/public/sscx/files/8/9/5/4/c/3/e/c/002_160120B3_OH_updated.nwb"
      oldPathContentType    = ContentType.parse("application/nwb").toOption
      _                    <- checkFor("file", oldPathFileId, xas).assertEquals(3)
      _                    <- assertS3Object(oldPathLocationRev1, oldPathContentType)
      _                    <- assertS3Object(oldPathLocationRev2, oldPathContentType)
      _                    <- assertFileAttributes(project, oldPathFileId)(
                                oldPathLocationRev2,
                                "002_160120B3_OH_updated.nwb",
                                oldPathContentType
                              )
      // File with a blank filename
      blankFilenameId       = iri"https://bbp.epfl.ch/neurosciencegraph/data/empty-filename"
      blankFilenameLocation = "/prefix/public/sscx/files/2/b/3/9/7/9/3/0/file"
      _                    <- checkFor("file", blankFilenameId, xas).assertEquals(1)
      _                    <- assertS3Object(blankFilenameLocation, Some(textPlain))
      _                    <- assertFileAttributes(project, blankFilenameId)(blankFilenameLocation, "file", Some(textPlain))
      // File with special characters in the filename
      specialCharsId        = iri"https://bbp.epfl.ch/neurosciencegraph/data/special-chars-filename"
      specialCharsLocation  = "/prefix/public/sscx/files/1/2/3/4/5/6/7/8/special [file].json"
      _                    <- checkFor("file", specialCharsId, xas).assertEquals(1)
      _                    <- assertS3Object(specialCharsLocation, Some(textPlain))
      _                    <- assertFileAttributes(project, specialCharsId)(specialCharsLocation, "special [file].json", Some(textPlain))
      // Local file containing a plus
      localPlusId           = iri"https://bbp.epfl.ch/neurosciencegraph/data/local-plus"
      localPlusLocation     = "/prefix/public/sscx/files/9/f/0/3/2/4/f/e/0925_Rhi13.3.13 cell 1 2 (superficial).asc"
      _                    <- checkFor("file", localPlusId, xas).assertEquals(1)
      _                    <- assertS3Object(localPlusLocation, Some(applicationOctetStream))
      _                    <- assertFileAttributes(project, localPlusId)(
                                localPlusLocation,
                                "0925_Rhi13.3.13 cell 1+2 (superficial).asc",
                                Some(applicationOctetStream)
                              )
      // Directory, should be skipped
      directoryId           = iri"https://bbp.epfl.ch/neurosciencegraph/data/directory"
      _                    <- checkFor("file", directoryId, xas).assertEquals(0)
      // Summary S3 check, 8 objects should have been imported in total
      _                    <- s3Client.listObjectsV2(targetBucket).map(_.keyCount().intValue()).assertEquals(8)
      // Summary report check, only the directory event should have been skipped
      _                     = assertEquals(report.progress(Files.entityType).success, 9L)
      _                     = assertEquals(report.progress(Files.entityType).dropped, 1L)
    } yield ()
  }

  private def assertFileAttributes(
      project: ProjectRef,
      id: Iri
  )(expectedLocation: String, expectedFileName: String, expectedContentType: Option[ContentType]) =
    fetchFileAttributes(project, id, xas).map { attributes =>
      assertEquals(UrlUtils.decode(attributes.location), expectedLocation)
      assertEquals(UrlUtils.decode(attributes.path), expectedLocation)
      assertEquals(attributes.filename, expectedFileName)
      assertEquals(attributes.mediaType, expectedContentType)
    }

  private def assertS3Object(key: String, contentType: Option[ContentType])(implicit location: Location): IO[Unit] =
    s3Client
      .headObject(targetBucket, key)
      .map { head =>
        assertEquals(head.fileSize, contentLength)
        assertEquals(head.digest, fileDigest)
        assertEquals(head.contentType, contentType)
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

  // The expected import report for the 00263821.json file, as well as for the /import/multi-part-import directory
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
