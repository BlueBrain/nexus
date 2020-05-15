package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.Done
import akka.actor.ActorSystem
import akka.event.Logging
import akka.persistence.cassandra.CassandraPluginConfig
import akka.persistence.cassandra.session.scaladsl.CassandraSession
import akka.persistence.query.Offset
import akka.stream.scaladsl.Source
import cats.effect._
import cats.implicits._
import ch.epfl.bluebrain.nexus.sourcing.projections.instances._
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import com.datastax.driver.core.Session
import com.google.common.util.concurrent.ListenableFuture
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}

import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.util.Try

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

  /**
    * Creates a delayed projections module that uses the provided cassandra session.
    *
    * @param session the cassandra session used by the projection
    */
  def apply[F[_], A: Encoder: Decoder](
      session: CassandraSession
  )(implicit as: ActorSystem, F: Async[F]): F[Projections[F, A]] = {
    val statements = new Statements(as)
    val cfg        = lookupConfig(as)
    ensureInitialized(session, cfg, statements) >> F.delay(new CassandraProjections(session, statements))
  }

  /**
    * Creates a delayed projections module. The underlying cassandra session is created automatically.
    */
  def apply[F[_], A: Encoder: Decoder](implicit as: ActorSystem, F: Async[F]): F[Projections[F, A]] =
    createSession[F].flatMap(session => apply(session))

  private class Statements(as: ActorSystem) {
    private val journalConfig = as.settings.config.getConfig("cassandra-journal")
    private val cfg           = new CassandraPluginConfig(as, journalConfig)

    val keyspace: String      = cfg.keyspace
    val progressTable: String = journalConfig.getString("projection-progress-table")
    val failuresTable: String = journalConfig.getString("projection-failures-table")

    val createKeyspace: String =
      s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
         |WITH REPLICATION = { 'class' : ${cfg.replicationStrategy} }""".stripMargin

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

  private def wrapFuture[F[_]: LiftIO, A](f: => ListenableFuture[A])(implicit ec: ExecutionContextExecutor): F[A] =
    wrapFuture {
      val promise = Promise[A]
      f.addListener(() => promise.complete(Try(f.get())), ec)
      promise.future
    }

  private def lookupConfig(as: ActorSystem): CassandraPluginConfig = {
    val journalConfig = as.settings.config.getConfig("cassandra-journal")
    new CassandraPluginConfig(as, journalConfig)
  }

  private def createSession[F[_]](implicit as: ActorSystem, F: Sync[F]): F[CassandraSession] = {
    val cfg = lookupConfig(as)
    val log = Logging(as, Projections.getClass)
    F.delay {
      new CassandraSession(
        as,
        cfg.sessionProvider,
        cfg.sessionSettings,
        as.dispatcher,
        log,
        metricsCategory = "projections",
        init = _ => Future.successful(Done)
      )
    }
  }

  private def ensureInitialized[F[_]](session: CassandraSession, cfg: CassandraPluginConfig, stmts: Statements)(
      implicit as: ActorSystem,
      F: Async[F]
  ): F[Unit] = {
    implicit val ec: ExecutionContextExecutor = as.dispatcher

    val underlying = wrapFuture(session.underlying())

    def keyspace(s: Session) =
      if (cfg.keyspaceAutoCreate) wrapFuture(s.executeAsync(stmts.createKeyspace)) >> F.unit
      else F.unit

    def progress(s: Session) =
      if (cfg.tablesAutoCreate) wrapFuture(s.executeAsync(stmts.createProgressTable)) >> F.unit
      else F.unit

    def failures(s: Session) =
      if (cfg.tablesAutoCreate) wrapFuture(s.executeAsync(stmts.createFailuresTable)) >> F.unit
      else F.unit

    for {
      s <- underlying
      _ <- keyspace(s)
      _ <- progress(s)
      _ <- failures(s)
    } yield ()
  }
}
