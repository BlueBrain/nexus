package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.LocalStackS3StorageClient
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie.{transactors, PostgresPassword, PostgresUser}
import ch.epfl.bluebrain.nexus.ship.ImportReport.Count
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{clearDB, expectedImportReport, getDistinctOrgProjects, uploadImportFileToS3}
import ch.epfl.bluebrain.nexus.testkit.config.SystemPropertyOverride
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.implicits._
import fs2.io.file.Path
import munit.catseffect.IOFixture
import munit.{AnyFixture, CatsEffectSuite}
import software.amazon.awssdk.services.s3.model.{CreateBucketRequest, PutObjectRequest, PutObjectResponse}

import java.nio.file.Paths
import java.time.Instant

class RunShipSuite extends NexusSuite with RunShipSuite.Fixture with LocalStackS3StorageClient.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]]  = List(mainFixture, localStackS3Client)
  private lazy val xas                            = mainFixture()
  private lazy val (s3Client: S3StorageClient, _) = localStackS3Client()

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    clearDB(xas).accepted
    ()
  }

  test("Run import from S3 providing a single file") {
    val path   = Path("/import/import.json")
    val bucket = "bucket"
    for {
      _ <- uploadImportFileToS3(s3Client, bucket, path)
      _ <- RunShip.s3Ship(s3Client.underlyingClient, bucket).run(path, None).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Run import from S3 providing a directory") {
    val directoryPath = Path("/import/multi-part-import")
    for {
      _ <- uploadImportFileToS3(s3Client, "bucket", Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.json"))
      _ <- uploadImportFileToS3(s3Client, "bucket", Path("/import/multi-part-import/2024-04-05T14:38:31.165389Z.success"))
      _ <- uploadImportFileToS3(s3Client, "bucket", Path("/import/multi-part-import/2024-04-06T11:34:31.165389Z.json"))
      _ <- RunShip
             .s3Ship(s3Client.underlyingClient, "bucket")
             .run(directoryPath, None)
             .assertEquals(expectedImportReport)
    } yield ()
  }

  test("Run import by providing the path to a file") {
    for {
      importFile <- asPath("import/import.json")
      _          <- RunShip.localShip.run(importFile, None).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Run import by providing the path to a directory") {
    for {
      importDirectory <- asPath("import/multi-part-import")
      _               <- RunShip.localShip.run(importDirectory, None).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Test the increment") {
    for {
      importFileWithTwoProjects <- asPath("import/two-projects.json")
      startFrom                  = Offset.at(2)
      _                         <- RunShip.localShip.run(importFileWithTwoProjects, None, startFrom).map { report =>
                                     assert(report.offset == Offset.at(2L))
                                     assert(thereIsOneProjectEventIn(report))
                                   }
    } yield ()
  }

  test("Import and map public/sscx to obp/somato") {
    for {
      externalConfigPath        <- loader.absolutePath("config/project-mapping-sscx.conf").map(x => Some(Path(x)))
      importFileWithTwoProjects <- asPath("import/import.json")
      _                         <- RunShip.localShip.run(importFileWithTwoProjects, externalConfigPath, Offset.start)
      _                         <- getDistinctOrgProjects(xas).map { projects =>
                                     assert(projects.size == 1)
                                     assert(projects.contains(("obp", "somato")))
                                   }
    } yield ()
  }

  private def asPath(path: String): IO[Path] = {
    ClasspathResourceLoader().absolutePath(path).map(Path(_))
  }

  private def thereIsOneProjectEventIn(report: ImportReport) =
    report.progress == Map(Projects.entityType -> Count(1L, 0L))

}

object RunShipSuite {

  def clearDB(xas: Transactors) =
    sql"""
         | DELETE FROM scoped_events; DELETE FROM scoped_states;
         |""".stripMargin.update.run.void.transact(xas.write)

  def getDistinctOrgProjects(xas: Transactors) =
    sql"""
         | SELECT DISTINCT org, project FROM scoped_events;
       """.stripMargin.query[(String, String)].to[List].transact(xas.read)

  // The expected import report for the import.json file, as well as for the /import/multi-part-import directory
  val expectedImportReport: ImportReport = ImportReport(
    Offset.at(9999999L),
    Instant.parse("2099-12-31T22:59:59.999Z"),
    Map(
      Projects.entityType  -> Count(5L, 0L),
      Resolvers.entityType -> Count(5L, 0L),
      Resources.entityType -> Count(1L, 0L),
      EntityType("xxx")    -> Count(0L, 1L)
    )
  )

  def uploadImportFileToS3(s3Client: S3StorageClient, bucket: String, path: Path): IO[PutObjectResponse] = {
    s3Client.underlyingClient.createBucket(CreateBucketRequest.builder().bucket(bucket).build) >>
      s3Client.underlyingClient
        .putObject(
          PutObjectRequest.builder.bucket(bucket).key(path.toString).build,
          Paths.get(getClass.getResource(path.toString).toURI)
        )
  }

  trait Fixture { self: CatsEffectSuite =>

    private def initConfig(postgres: PostgresContainer) =
      Map(
        "ship.database.access.host"        -> postgres.getHost,
        "ship.database.access.port"        -> postgres.getMappedPort(5432).toString,
        "ship.database.tables-autocreate"  -> "true",
        "ship.organizations.values.public" -> "The public organization",
        "ship.organizations.values.obp"    -> "The OBP organization"
      )

    private val resource: Resource[IO, Transactors] = transactors(
      PostgresContainer.resource(PostgresUser, PostgresPassword).flatTap { pg =>
        SystemPropertyOverride(initConfig(pg)).void
      },
      PostgresUser,
      PostgresPassword
    )

    val mainFixture: IOFixture[Transactors] = ResourceSuiteLocalFixture("main", resource)
  }

}
