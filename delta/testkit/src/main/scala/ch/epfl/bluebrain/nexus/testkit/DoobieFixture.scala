package ch.epfl.bluebrain.nexus.testkit

import cats.effect._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import com.zaxxer.hikari.HikariDataSource
import doobie._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

trait DoobieFixture extends AnyWordSpecLike with BeforeAndAfterAll with PostgresDocker with TestHelpers {

  private def loadDDL(path: String): Fragment = Fragment.const0(contentOf(path))

  var xas: Transactors = _

  override def beforeAll(): Unit = {
    implicit val s: Scheduler = Scheduler.global
    super.beforeAll()
    val ioXas                 = for {
      _  <- Task.delay(Class.forName("org.postgresql.Driver"))
      ds <- Task.delay {
              val ds = new HikariDataSource
              ds.setJdbcUrl(s"jdbc:postgresql://${container.getHost}:${container.getMappedPort(5432)}/")
              ds.setUsername("postgres")
              ds.setPassword("postgres")
              ds.setDriverClassName("org.postgresql.Driver")
              ds
            }
      t  <- Task.delay {
              HikariTransactor[Task](ds, s, Blocker.liftExecutionContext(s))
            }
    } yield Transactors.shared(t)
    xas = ioXas.runSyncUnsafe()
    val createTables          = loadDDL("/scripts/schema.ddl").update.run
    val dropTables            = loadDDL("/scripts/drop-tables.ddl").update.run
    (dropTables, createTables).mapN(_ + _).transact(xas.write).void.runSyncUnsafe()
  }

}
