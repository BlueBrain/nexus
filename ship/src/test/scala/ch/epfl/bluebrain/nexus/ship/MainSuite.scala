package ch.epfl.bluebrain.nexus.ship

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.delta.sourcing.postgres.Doobie.{PostgresPassword, PostgresUser}
import ch.epfl.bluebrain.nexus.testkit.config.SystemPropertyOverride
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import fs2.io.file.Path
import munit.{AnyFixture, CatsEffectSuite}
import munit.catseffect.IOFixture

class MainSuite extends NexusSuite with MainSuite.Fixture {

  override def munitFixtures: Seq[AnyFixture[_]] = List(mainFixture)

  test("Run import") {
    for {
      importFile <- ClasspathResourceLoader().absolutePath("import/import.json").map(Path(_))
      _          <- Main.run(importFile, None)
    } yield ()
  }

  test("Show config") {
    Main.showConfig(None)
  }

}

object MainSuite {

  trait Fixture { self: CatsEffectSuite =>

    private def initConfig(postgres: PostgresContainer) =
      Map(
        "ship.database.access.host"        -> postgres.getHost,
        "ship.database.access.port"        -> postgres.getMappedPort(5432).toString,
        "ship.database.tables-autocreate"  -> "true",
        "ship.organizations.values.public" -> "The public organization"
      )

    private def resource(): Resource[IO, Unit] =
      PostgresContainer
        .resource(PostgresUser, PostgresPassword)
        .flatMap { postgres =>
          SystemPropertyOverride(initConfig(postgres))
        }
        .void

    val mainFixture: IOFixture[Unit] = ResourceSuiteLocalFixture("main", resource())
  }

}
