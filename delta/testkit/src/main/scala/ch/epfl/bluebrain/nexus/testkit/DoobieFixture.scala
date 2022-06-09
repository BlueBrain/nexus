package ch.epfl.bluebrain.nexus.testkit

import cats.syntax.all._

import ch.epfl.bluebrain.nexus.delta.kernel.Transactors
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresDocker
import doobie._
import doobie.implicits._
import monix.bio.Task
import monix.execution.Scheduler
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike

trait DoobieFixture extends AnyWordSpecLike with BeforeAndAfterAll with PostgresDocker with TestHelpers {

  private def loadDDL(path: String): Fragment = Fragment.const0(contentOf(path))

  var xas: Transactors = _

  override def beforeAll(): Unit = {
    super.beforeAll()
    xas = Transactors.shared(
      Transactor.fromDriverManager[Task](
        "org.postgresql.Driver",
        s"jdbc:postgresql://${container.getHost}:${container.getMappedPort(5432)}/",
        "postgres",
        "postgres"
      )
    )
    implicit val s: Scheduler = Scheduler.global
    val createTables          = loadDDL("/scripts/schema.ddl").update.run
    val dropTables            = loadDDL("/scripts/drop-tables.ddl").update.run
    (dropTables, createTables).mapN(_ + _).transact(xas.write).void.runSyncUnsafe()
  }

}
