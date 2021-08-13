package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.actor.typed.ActorSystem
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.testkit.IOValues
import doobie.Query0
import doobie.implicits._
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

@DoNotDiscover
class PostgresDatabaseDefinitionSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with Eventually
    with ScalaFutures {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 50.millis)

  implicit private val actorSystem: ActorSystem[Nothing] = null

  private val dbConfig       = DatabaseConfig(DatabaseFlavour.Postgres, PostgresSpecs.postgresConfig, null, false)
  private val definition     = DatabaseDefinitions(dbConfig).accepted
  private val postgresTables = Set(
    "snapshot",
    "event_journal",
    "event_tag",
    "projections_progress",
    "projections_errors"
  )

  private val fetchTables: Query0[String] =
    fr"""|SELECT table_name
         |FROM information_schema.tables
         |WHERE TABLE_TYPE = 'BASE TABLE' AND TABLE_SCHEMA='public' """.stripMargin
      .query[String]

  "A Postgres Database definition" should {
    "be initialized" in {
      definition.initialize.accepted
      eventually {
        val xa = PostgresSpecs.postgresConfig.transactor
        fetchTables.to[List].transact(xa).accepted.toSet shouldEqual postgresTables
      }
    }
  }

}
