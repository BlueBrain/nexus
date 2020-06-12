package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import akka.stream.scaladsl.Source
import cats.effect._
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.instances._
import com.typesafe.config.{Config, ConfigValueType}
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
  * A Projection represents the process to transforming an event stream into a format that's efficient for consumption.
  * In terms of CQRS, the events represents the format in which data is written to the primary store (the write
  * model) while the result of a projection represents the data in a consumable format (the read model).
  *
  * Projections replay an event stream
  */
trait Projections[F[_], A] {

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  def recordProgress(id: String, progress: ProjectionProgress): F[Unit]

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  def progress(id: String): F[ProjectionProgress]

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id            the project identifier
    * @param persistenceId the persistenceId to record
    * @param sequenceNr    the sequence number to record
    * @param offset        the offset to record
    * @param value         the value to be recorded
    */
  def recordFailure(id: String, persistenceId: String, sequenceNr: Long, offset: Offset, value: A): F[Unit]

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  def failures(id: String): Source[(A, Offset), _]
}

object Projections {
  val cassandraDefaultConfigPath = "akka.persistence.cassandra"

  def journalConfig(implicit as: ActorSystem) =
    as.settings.config.getConfig(cassandraDefaultConfigPath).getConfig("journal")

  /**
    * Creates a delayed projections module that uses the provided cassandra session.
    *
    * @param session the cassandra session used by the projection
    */
  def apply[F[_], A: Encoder: Decoder](
      session: CassandraSession
  )(implicit as: ActorSystem, F: Async[F]): F[Projections[F, A]] = {
    val statements                  = new Statements(as)
    val journalCfg                  = journalConfig
    val keyspaceAutoCreate: Boolean = journalCfg.getBoolean("keyspace-autocreate")
    val tablesAutoCreate: Boolean   = journalCfg.getBoolean("tables-autocreate")
    ensureInitialized(session, keyspaceAutoCreate, tablesAutoCreate, statements) >> F.delay(
      new CassandraProjections(session, statements)
    )
  }

  /**
    * Creates a delayed projections module. The underlying cassandra session is created automatically.
    */
  def apply[F[_], A: Encoder: Decoder](implicit as: ActorSystem, F: Async[F]): F[Projections[F, A]] =
    createSession[F].flatMap(session => apply(session))

  private class Statements(as: ActorSystem) {
    val journalCfg: Config          = journalConfig(as)
    val keyspace: String            = journalCfg.getString("keyspace")
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

    val recordProgressQuery: String =
      s"UPDATE $keyspace.$progressTable SET progress = ? WHERE projection_id = ?"

    val progressQuery: String =
      s"SELECT progress FROM $keyspace.$progressTable WHERE projection_id = ?"

    val recordFailureQuery: String =
      s"""INSERT INTO $keyspace.$failuresTable (projection_id, offset, persistence_id, sequence_nr, value)
         |VALUES (?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

    val failuresQuery: String =
      s"SELECT offset, value from $keyspace.$failuresTable WHERE projection_id = ? ALLOW FILTERING"
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

  private class CassandraProjections[F[_], A: Encoder: Decoder](
      session: CassandraSession,
      stmts: Statements
  )(implicit as: ActorSystem, F: Async[F])
      extends Projections[F, A] {
    import as.dispatcher
    override def recordProgress(id: String, progress: ProjectionProgress): F[Unit] =
      wrapFuture(session.executeWrite(stmts.recordProgressQuery, progress.asJson.noSpaces, id)) >> F.unit

    override def progress(id: String): F[ProjectionProgress] =
      wrapFuture(session.selectOne(stmts.progressQuery, id)).flatMap {
        case Some(row) => F.fromTry(decode[ProjectionProgress](row.getString("progress")).toTry)
        case None      => F.pure(NoProgress)
      }

    override def recordFailure(id: String, persistenceId: String, sequenceNr: Long, offset: Offset, value: A): F[Unit] =
      wrapFuture(
        session.executeWrite(
          stmts.recordFailureQuery,
          id,
          offset.asJson.noSpaces,
          persistenceId,
          sequenceNr: java.lang.Long,
          value.asJson.noSpaces
        )
      ) >> F.unit

    override def failures(id: String): Source[(A, Offset), _] =
      session
        .select(stmts.failuresQuery, id)
        .map { row => (decode[A](row.getString("value")), decode[Offset](row.getString("offset"))).tupled }
        .collect { case Right(value) => value }
  }

  private def wrapFuture[F[_]: LiftIO, A](f: => Future[A])(implicit ec: ExecutionContextExecutor): F[A] = {
    implicit val contextShift: ContextShift[IO] = IO.contextShift(ec)
    IO.fromFuture(IO(f)).to[F]
  }

  private def createSession[F[_]](implicit as: ActorSystem, F: Sync[F]): F[CassandraSession] =
    F.delay {
      CassandraSessionRegistry.get(as).sessionFor(CassandraSessionSettings(cassandraDefaultConfigPath))
    }

  private def ensureInitialized[F[_]](
      session: CassandraSession,
      keyspaceAutoCreate: Boolean,
      tablesAutoCreate: Boolean,
      stmts: Statements
  )(implicit
      as: ActorSystem,
      F: Async[F]
  ): F[Unit] = {
    implicit val ec: ExecutionContextExecutor = as.dispatcher

    def keyspace =
      if (keyspaceAutoCreate) wrapFuture(session.executeDDL(stmts.createKeyspace)) >> F.unit
      else F.unit

    def progress =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(stmts.createProgressTable)) >> F.unit
      else F.unit

    def failures =
      if (tablesAutoCreate) wrapFuture(session.executeDDL(stmts.createFailuresTable)) >> F.unit
      else F.unit

    for {
      _ <- keyspace
      _ <- progress
      _ <- failures
    } yield ()
  }
}
