package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie
import ch.epfl.bluebrain.nexus.ship.ImportReport.Count
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.{checkFor, expectedImportReport, getDistinctOrgProjects}
import ch.epfl.bluebrain.nexus.ship.config.ShipConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import doobie.implicits._
import fs2.io.file.Path
import munit.AnyFixture

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
      _      <- RunShip(events, inputConfig, xas).assertEquals(expectedImportReport)
      _      <- checkFor("elasticsearch", nxv + "defaultElasticSearchIndex", xas).assertEquals(1)
      _      <- checkFor("blazegraph", nxv + "defaultSparqlIndex", xas).assertEquals(1)
    } yield ()
  }

  test("Run import by providing the path to a directory") {
    for {
      events <- eventsStream("import/multi-part-import")
      _      <- RunShip(events, inputConfig, xas).assertEquals(expectedImportReport)
    } yield ()
  }

  test("Test the increment") {
    val start = Offset.at(2)
    for {
      events <- eventsStream("import/two-projects.json", offset = start)
      _      <- RunShip(events, inputConfig, xas).map { report =>
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
      _      <- RunShip(events, configWithProjectMapping, xas)
      _      <- getDistinctOrgProjects(xas).map { project =>
                  assertEquals(project, target)
                }
    } yield ()
  }

  private def thereIsOneProjectEventIn(report: ImportReport) =
    report.progress == Map(Projects.entityType -> Count(1L, 0L))

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

}
