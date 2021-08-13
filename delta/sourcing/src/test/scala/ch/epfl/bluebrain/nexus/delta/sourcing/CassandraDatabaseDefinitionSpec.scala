package ch.epfl.bluebrain.nexus.delta.sourcing

import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Sink
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{DatabaseConfig, DatabaseFlavour}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.AkkaPersistenceCassandraSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.AkkaPersistenceCassandraSpec.cassandraConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import ch.epfl.bluebrain.nexus.testkit.IOValues
import org.scalatest.DoNotDiscover
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

@DoNotDiscover
class CassandraDatabaseDefinitionSpec
    extends AnyWordSpecLike
    with Matchers
    with IOValues
    with Eventually
    with ScalaFutures {
  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 50.millis)

  implicit val actorSystem: ActorSystem[Nothing] = AkkaPersistenceCassandraSpec.actorSystem
  import monix.execution.Scheduler.Implicits.global

  private val dbConfig        = DatabaseConfig(DatabaseFlavour.Cassandra, null, cassandraConfig, false)
  private val definition      = DatabaseDefinitions(dbConfig).accepted
  private val cassandraTables = Set(
    "all_persistence_ids",
    "messages",
    "metadata",
    "projections_errors",
    "projections_progress",
    "tag_scanning",
    "tag_views",
    "tag_write_progress"
  )

  "A Cassandra Database definition" should {
    "be initialized" in {
      definition.initialize.accepted
      eventually {
        val session = CassandraUtils.session.accepted
        val tables  = session
          .select(s"select table_name from system_schema.tables where keyspace_name = '${cassandraConfig.keyspace}';")
          .map(_.getString("table_name"))
          .runWith(Sink.fold(Set.empty[String])(_ + _))
          .futureValue
        tables shouldEqual cassandraTables

        val tablesSnapshot = session
          .select(
            s"select table_name from system_schema.tables where keyspace_name = '${cassandraConfig.keyspace}_snapshot';"
          )
          .map(_.getString("table_name"))
          .runWith(Sink.fold(Set.empty[String])(_ + _))
          .futureValue
        tablesSnapshot shouldEqual Set("snapshots")
      }
    }
  }

}
