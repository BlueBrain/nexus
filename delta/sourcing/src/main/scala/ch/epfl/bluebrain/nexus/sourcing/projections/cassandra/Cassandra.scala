package ch.epfl.bluebrain.nexus.sourcing.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import com.typesafe.config.{Config, ConfigValueType}
import monix.bio.Task

object Cassandra {

  /**
   * Configuration when using Cassandra to persist data
   *
   * @param keyspace keyspace containing the tables
   * @param keyspaceAutoCreate if we want to create the keyspace if it doesn't exist yet
   * @param tablesAutoCreate if we want to create the tables if it doesn't exist yet
   * @param replicationStrategy the expected replication strategy for the keyspace
   */
  final case class CassandraConfig(
      keyspace: String,
      keyspaceAutoCreate: Boolean,
      tablesAutoCreate: Boolean,
      replicationStrategy: String
  )

  import scala.jdk.CollectionConverters._

  val defaultPath = "akka.persistence.cassandra"

  def session(as: ActorSystem[Nothing]): Task[CassandraSession] =
    Task.delay {
      CassandraSessionRegistry
        .get(as)
        .sessionFor(CassandraSessionSettings(defaultPath))
    }

  def from(journalCfg: Config): CassandraConfig = {
    val keyspace: String = journalCfg.getString("keyspace")

    val keyspaceAutoCreate: Boolean = journalCfg.getBoolean("keyspace-autocreate")
    val tablesAutoCreate: Boolean   = journalCfg.getBoolean("tables-autocreate")

    val replicationStrategy: String = getReplicationStrategy(
      journalCfg.getString("replication-strategy"),
      journalCfg.getInt("replication-factor"),
      getListFromConfig(journalCfg, "data-center-replication-factors")
    )

    CassandraConfig(
      keyspace,
      keyspaceAutoCreate,
      tablesAutoCreate,
      replicationStrategy
    )
  }

  /**
    * Ported from Akka Persistence Cassandra private API
    *
    * @see [[akka.persistence.cassandra.PluginSettings.getReplicationStrategy]]
    */
  private def getReplicationStrategy(
      strategy: String,
      replicationFactor: Int,
      dataCenterReplicationFactors: Seq[String]
  ): String = {

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

}
