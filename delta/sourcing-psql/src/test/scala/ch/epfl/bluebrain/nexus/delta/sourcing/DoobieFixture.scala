package ch.epfl.bluebrain.nexus.delta.sourcing

import cats.effect.Blocker
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker.{PostgresPassword, PostgresUser}
import com.zaxxer.hikari.HikariDataSource
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import monix.bio.Task
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
      val ioXas = for {
        _ <- Task.delay(Class.forName("org.postgresql.Driver"))
        ds <- Task.delay {
          val ds = new HikariDataSource
          ds.setJdbcUrl(s"jdbc:postgresql://${container.getHost}:${container.getMappedPort(5432)}/")
          ds.setUsername("postgres")
          ds.setPassword("postgres")
          ds.setDriverClassName("org.postgresql.Driver")
          ds
        }
        t <- Task.delay {
          HikariTransactor[Task](ds, s ,Blocker.liftExecutionContext(s))
        }
      } yield Transactors.shared(t)
      xas = ioXas.runSyncUnsafe()
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
