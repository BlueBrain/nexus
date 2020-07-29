package ch.epfl.bluebrain.nexus.sourcingnew.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.{Async, ContextShift, IO}
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcingnew.projections.SchemaManager
import com.typesafe.config.{Config, ConfigValueType}

import scala.jdk.CollectionConverters._

class CassandraSchemaManager[F[_]](session: CassandraSession,
                                   journalCfg: Config,
                                   as: ActorSystem[Nothing])
                                  (implicit F: Async[F]) extends SchemaManager[F] {

  val keyspace: String            = journalCfg.getString("keyspace")

  val keyspaceAutoCreate: Boolean = journalCfg.getBoolean("keyspace-autocreate")
  val tablesAutoCreate: Boolean   = journalCfg.getBoolean("tables-autocreate")

  val replicationStrategy: String = getReplicationStrategy(
    journalCfg.getString("replication-strategy"),
    journalCfg.getInt("replication-factor"),
    getListFromConfig(journalCfg, "data-center-replication-factors")
  )

  val progressTable: String       = journalCfg.getString("projection-progress-table")
  val failuresTable: String       = journalCfg.getString("projection-failures-table")

  val createKeyspace: String =
    s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
       |WITH REPLICATION = { 'class' : $replicationStrategy }""".stripMargin

  val createProgressTable: String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$progressTable (
       |projection_id varchar primary key, progress text)""".stripMargin

  val createFailuresTable: String =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$failuresTable (
       |projection_id varchar, offset text, persistence_id text, sequence_nr bigint, value text,
       |PRIMARY KEY (projection_id, offset, persistence_id, sequence_nr))
       |WITH CLUSTERING ORDER BY (offset ASC)""".stripMargin

  /**
    * Ported from Akka Persistence Cassandra private API
    *
    * @see [[akka.persistence.cassandra.PluginSettings.getReplicationStrategy]]
    */
  private def getReplicationStrategy(strategy: String,
                                     replicationFactor: Int,
                                     dataCenterReplicationFactors: Seq[String]): String = {

    def getDataCenterReplicationFactorList(dcrfList: Seq[String]): String = {
      val result: Seq[String] = dcrfList match {
        case null | Nil =>
          throw new IllegalArgumentException(
            "data-center-replication-factors cannot be empty when using NetworkTopologyStrategy."
          )
        case dcrfs      =>
          dcrfs.map { dataCenterWithReplicationFactor =>
            dataCenterWithReplicationFactor.split(":") match {
              case Array(dataCenter, replicationFactor) =>
                s"'$dataCenter':$replicationFactor"
              case msg                                  =>
                throw new IllegalArgumentException(
                  s"A data-center-replication-factor must have the form [dataCenterName:replicationFactor] but was: $msg."
                )
            }
          }
      }
      result.mkString(",")
    }

    strategy.toLowerCase() match {
      case "simplestrategy"          =>
        s"'SimpleStrategy','replication_factor':$replicationFactor"
      case "networktopologystrategy" =>
        s"'NetworkTopologyStrategy',${getDataCenterReplicationFactorList(dataCenterReplicationFactors)}"
      case unknownStrategy           =>
        throw new IllegalArgumentException(s"$unknownStrategy as replication strategy is unknown and not supported.")
    }
  }

  /**
    * Ported from Akka Persistence Cassandra private API
    *
    * @see [[akka.persistence.cassandra.getListFromConfig]]
    */
  private def getListFromConfig(config: Config, key: String): List[String] = {
    config.getValue(key).valueType() match {
      case ConfigValueType.LIST   => config.getStringList(key).asScala.toList
      // case ConfigValueType.OBJECT is needed to handle dot notation (x.0=y x.1=z) due to Typesafe Config implementation quirk.
      // https://github.com/lightbend/config/blob/master/config/src/main/java/com/typesafe/config/impl/DefaultTransformer.java#L83
      case ConfigValueType.OBJECT => config.getStringList(key).asScala.toList
      case ConfigValueType.STRING => config.getString(key).split(",").toList
      case _                      => throw new IllegalArgumentException(s"$key should be a List, Object or String")
    }
  }


  override def migrate(): F[Unit] = {
    //implicit val ec: ExecutionContextExecutor = as.executionContext
    import ch.epfl.bluebrain.nexus.sourcingnew.projections.Projection._
    implicit val cs: ContextShift[IO] = IO.contextShift(as.executionContext)

    def keyspace =
      if (keyspaceAutoCreate) wrapFuture(session.executeDDL(createKeyspace)) >> F.unit
      else F.unit

    def progress =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(createProgressTable)) >> F.unit
      else F.unit

    def failures =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(createFailuresTable)) >> F.unit
      else F.unit

    for {
      _ <- keyspace
      _ <- progress
      _ <- failures
    } yield ()
  }
}
