package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{PostgresPassword, PostgresUser}
import doobie._
import doobie.implicits._
import monix.execution.Scheduler
import munit.Suite

import scala.concurrent.duration._
import scala.jdk.DurationConverters._

trait DoobieFixture extends TestHelpers { self: Suite =>

  private def loadDDL(path: String): Fragment = Fragment.const0(contentOf(path))

  val doobie: Fixture[Transactors] = new Fixture[Transactors]("doobie") {

    private var container: PostgresContainer = _
    private var xas: Transactors             = _

    def apply(): Transactors = xas

    override def beforeAll(): Unit = {
      implicit val s: Scheduler = Scheduler.global
      container = new PostgresContainer(PostgresUser, PostgresPassword)
        .withReuse(false)
        .withStartupTimeout(60.seconds.toJava)
      container.start()
      xas =
        Transactors.sharedFrom(container.getHost, container.getMappedPort(5432), "postgres", "postgres").runSyncUnsafe()
      val createTables          = loadDDL("/scripts/schema.ddl").update.run
      val dropTables            = loadDDL("/scripts/drop-tables.ddl").update.run
      (dropTables, createTables).mapN(_ + _).transact(xas.write).void.runSyncUnsafe()
    }

    override def afterAll(): Unit = {
      container.stop()
    }
  }
}

object DoobieFixture {
  val PostgresUser     = "postgres"
  val PostgresPassword = "postgres"
}
