package ch.epfl.bluebrain.nexus.delta.sourcing2

import cats.implicits._
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import doobie._
import doobie.implicits._
import doobie.util.transactor.Transactor
import monix.bio.Task
import org.scalatest.{BeforeAndAfterAll, Suite}

trait PostgresSetup extends Suite with BeforeAndAfterAll with IOValues with TestHelpers {

  lazy val xa: Transactor.Aux[Task, Unit] = Transactor.fromDriverManager[Task](
    "org.postgresql.Driver",
    "jdbc:postgresql://localhost:5432/",
    "postgres",
    "postgres"
  )

  private def loadDDL(path: String) = Fragment.const0(contentOf(path))

  private lazy val createTables = loadDDL("/scripts/schema.ddl").update.run
  private lazy val dropTables   = loadDDL("/scripts/drop-tables.ddl").update.run

  override def beforeAll(): Unit = {
    super.beforeAll()
    (dropTables, createTables).mapN(_ + _).transact(xa).void.accepted
  }

}
