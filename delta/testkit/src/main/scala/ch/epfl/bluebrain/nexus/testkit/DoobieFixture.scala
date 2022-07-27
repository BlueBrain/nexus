package ch.epfl.bluebrain.nexus.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{PostgresPassword, PostgresUser}
import monix.execution.Scheduler
import munit.Suite

import scala.concurrent.duration._
import scala.jdk.DurationConverters._

trait DoobieFixture extends TestHelpers { self: Suite =>

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

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
      (xas.execDDL("/scripts/drop-tables.ddl") >> xas.execDDL("/scripts/schema.ddl")).runSyncUnsafe()
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
