package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.sdk.projects.Projects
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.Resolvers
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie.{PostgresPassword, PostgresUser, transactors}
import ch.epfl.bluebrain.nexus.ship.ImportReport.Count
import ch.epfl.bluebrain.nexus.ship.RunShipSuite.clearDB
import ch.epfl.bluebrain.nexus.testkit.config.SystemPropertyOverride
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.implicits._
import fs2.io.file.Path
import munit.catseffect.IOFixture
import munit.{AnyFixture, CatsEffectSuite}

import java.time.Instant

class RunShipSuite extends NexusSuite with RunShipSuite.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(mainFixture)
  private lazy val xas                           = mainFixture()

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    clearDB(xas).accepted
    ()
  }

  test("Run import") {
    val expected = ImportReport(
      Offset.at(9999999L),
      Instant.parse("2099-12-31T22:59:59.999Z"),
      Map(
        Projects.entityType  -> Count(5L, 0L),
        Resolvers.entityType -> Count(5L, 0L),
        Resources.entityType -> Count(1L, 0L),
        EntityType("xxx")    -> Count(0L, 1L)
      )
    )
    for {
      importFile <- asPath("import/import.json")
      _          <- new RunShip().run(importFile, None).assertEquals(expected)
    } yield ()
  }

  test("Test the increment") {
    for {
      importFileWithTwoProjects <- asPath("import/two-projects.json")
      startFrom                  = Offset.at(2)
      _                         <- new RunShip().run(importFileWithTwoProjects, None, startFrom).map { report =>
                                     assert(report.offset == Offset.at(2L))
                                     assert(thereIsOneProjectEventIn(report))
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

  trait Fixture { self: CatsEffectSuite =>

    private def initConfig(postgres: PostgresContainer) =
      Map(
        "ship.database.access.host"        -> postgres.getHost,
        "ship.database.access.port"        -> postgres.getMappedPort(5432).toString,
        "ship.database.tables-autocreate"  -> "true",
        "ship.organizations.values.public" -> "The public organization"
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
