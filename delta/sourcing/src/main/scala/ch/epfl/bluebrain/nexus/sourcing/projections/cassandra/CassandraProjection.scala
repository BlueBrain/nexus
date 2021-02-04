package ch.epfl.bluebrain.nexus.sourcing.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.Severity.{Failure, Warning}
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.CassandraProjection._
import ch.epfl.bluebrain.nexus.sourcing.projections._
import com.typesafe.scalalogging.Logger
import fs2.Stream
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import monix.bio.{Task, UIO}
import streamz.converter._

import java.time.Instant
import java.util.UUID

/**
  * Implementation of [[Projection]] for Cassandra
  */
private[projections] class CassandraProjection[A: Encoder: Decoder] private (
    session: CassandraSession,
    empty: => A,
    throwableToString: Throwable => String,
    config: CassandraConfig
)(implicit as: ActorSystem[Nothing], clock: Clock[UIO])
    extends Projection[A] {

  implicit private val materializer: Materializer = Materializer.createMaterializer(as)

  private val recordProgressQuery: String =
    s"UPDATE ${config.keyspace}.projections_progress SET offset = ?, timestamp = ?, processed = ?, discarded = ?, warnings = ?, failed = ?, value = ? WHERE projection_id = ?"

  private val progressQuery: String =
    s"SELECT offset, timestamp, processed, discarded, warnings, failed, value FROM ${config.keyspace}.projections_progress WHERE projection_id = ?"

  private val recordErrorQuery: String =
    s"""INSERT INTO ${config.keyspace}.projections_errors (projection_id, offset, timestamp, persistence_id, sequence_nr, value, severity, error_type, message)
       |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  private val failuresQuery: String =
    s"SELECT offset, timestamp, persistence_id, sequence_nr, value, severity,  error_type, message from ${config.keyspace}.projections_errors WHERE projection_id = ?"

  private def offsetToUUID(offset: Offset): Option[UUID] =
    offset match {
      case TimeBasedUUID(uuid) => Some(uuid)
      case _                   => None
    }

  private def parseOffset(value: UUID): Offset = Option(value).fold[Offset](NoOffset)(TimeBasedUUID)

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit] =
    instant.flatMap { timestamp =>
      Task.deferFuture {
        logger.info(s"Recording projection progress {}} at offset {}", id, progress.offset)
        session.executeWrite(
          recordProgressQuery,
          offsetToUUID(progress.offset).orNull,
          timestamp.toEpochMilli: java.lang.Long,
          progress.processed: java.lang.Long,
          progress.discarded: java.lang.Long,
          progress.warnings: java.lang.Long,
          progress.failed: java.lang.Long,
          progress.value.asJson.noSpaces,
          id.value
        )
      }
    }.void

  override def progress(id: ProjectionId): Task[ProjectionProgress[A]] =
    Task.deferFuture(session.selectOne(progressQuery, id.value)).flatMap {
      case Some(row) =>
        Task.fromEither(decode[A](row.getString("value"))).map { value =>
          ProjectionProgress(
            parseOffset(row.getUuid("offset")),
            Instant.ofEpochMilli(row.getLong("timestamp")),
            row.getLong("processed"),
            row.getLong("discarded"),
            row.getLong("warnings"),
            row.getLong("failed"),
            value
          )
        }
      case None      => Task.pure(NoProgress(empty))
    }

  override def recordWarnings(id: ProjectionId, message: SuccessMessage[A]): Task[Unit] =
    Task.when(message.warnings.nonEmpty) {
      instant.flatMap { timestamp =>
        Task.deferFuture(
          session.executeWrite(
            recordErrorQuery,
            id.value,
            offsetToUUID(message.offset).orNull,
            timestamp.toEpochMilli: java.lang.Long,
            message.persistenceId,
            message.sequenceNr: java.lang.Long,
            message.value.asJson.noSpaces,
            Severity.Warning.toString,
            null,
            message.warningMessage
          )
        )
      }.void
    }

  override def recordFailure(
      id: ProjectionId,
      errorMessage: ErrorMessage
  ): Task[Unit] = {
    logger.error(s"Recording error during projection {} at offset {}", id, errorMessage.offset)
    def errorWrite(instant: Instant) = errorMessage match {
      case c: CastFailedMessage =>
        Task.deferFuture(
          session.executeWrite(
            recordErrorQuery,
            id.value,
            offsetToUUID(c.offset).orNull,
            instant.toEpochMilli: java.lang.Long,
            c.persistenceId,
            c.sequenceNr: java.lang.Long,
            null,
            Severity.Failure.toString,
            "ClassCastException",
            c.errorMessage
          )
        )
      case f: FailureMessage[A] =>
        Task.deferFuture(
          session.executeWrite(
            recordErrorQuery,
            id.value,
            offsetToUUID(f.offset).orNull,
            instant.toEpochMilli: java.lang.Long,
            f.persistenceId,
            f.sequenceNr: java.lang.Long,
            f.value.asJson.noSpaces,
            Severity.Failure.toString,
            ClassUtils.simpleName(f.throwable),
            throwableToString(f.throwable)
          )
        )
    }
    instant.flatMap(errorWrite).void
  }

  override def errors(id: ProjectionId): Stream[Task, ProjectionError[A]] =
    session
      .select(failuresQuery, id.value)
      .toStream[Task](_ => ())
      .map { row =>
        Severity.fromString(row.getString("severity")) match {
          case Warning =>
            ProjectionWarning(
              parseOffset(row.getUuid("offset")),
              Instant.ofEpochMilli(row.getLong("timestamp")),
              row.getString("message"),
              row.getString("persistence_id"),
              row.getLong("sequence_nr"),
              Option(row.getString("value")).flatMap(decode[A](_).toOption)
            )
          case Failure =>
            ProjectionFailure(
              parseOffset(row.getUuid("offset")),
              Instant.ofEpochMilli(row.getLong("timestamp")),
              row.getString("message"),
              row.getString("persistence_id"),
              row.getLong("sequence_nr"),
              Option(row.getString("value")).flatMap(decode[A](_).toOption),
              row.getString("error_type")
            )
        }

      }
}

private class CassandraProjectionInitialization(session: CassandraSession, config: CassandraConfig) {

  private val projectionsErrorsTable   = "projections_errors"
  private val projectionsProgressTable = "projections_progress"

  // TODO: The replication factor could be fetched from akka configuration but it is pretty complicated.
  private val createKeyspace: String =
    s"""CREATE KEYSPACE IF NOT EXISTS ${config.keyspace}
       |WITH REPLICATION = { 'class' : 'SimpleStrategy','replication_factor':1 }""".stripMargin

  private val createProjectionsProgressDll =
    s"""CREATE TABLE IF NOT EXISTS ${config.keyspace}.$projectionsProgressTable
       |(
       |    projection_id text primary key,
       |    offset        timeuuid,
       |    timestamp     bigint,
       |    processed     bigint,
       |    discarded     bigint,
       |    warnings      bigint,
       |    failed        bigint,
       |    value         text,
       |)""".stripMargin

  private val createProjectionsFailuresDll =
    s"""CREATE TABLE IF NOT EXISTS ${config.keyspace}.$projectionsErrorsTable
       |(
       |    projection_id  text,
       |    offset         timeuuid,
       |    timestamp      bigint,
       |    persistence_id text,
       |    sequence_nr    bigint,
       |    value          text,
       |    severity       text,
       |    error_type     text,
       |    message        text,
       |    PRIMARY KEY ((projection_id), timestamp, persistence_id, sequence_nr)
       |)
       |    WITH CLUSTERING ORDER BY (timestamp ASC, persistence_id ASC, sequence_nr ASC)""".stripMargin

  def initialize(): Task[Unit] = {

    def keyspace =
      Task.when(config.keyspaceAutocreate) {
        Task.deferFuture(session.executeDDL(createKeyspace)).void >>
          Task.delay(logger.info(s"Created keyspace '${config.keyspace}'"))
      }

    def projectionsProgress =
      Task.when(config.tablesAutocreate) {
        Task.deferFuture(session.executeDDL(createProjectionsProgressDll)).void >>
          Task.delay(logger.info(s"Created table '${config.keyspace}.$projectionsProgressTable'"))
      }

    def projectionsFailures =
      Task.when(config.tablesAutocreate) {
        Task.deferFuture(session.executeDDL(createProjectionsFailuresDll)).void >>
          Task.delay(logger.info(s"Created table '${config.keyspace}.$projectionsErrorsTable'"))
      }

    keyspace >> projectionsProgress >> projectionsFailures
  }
}

object CassandraProjection {

  private[cassandra] val logger: Logger = Logger[CassandraProjection.type]

  /**
    * @return a cassandra session from the actor system registry
    */
  private[projections] def session(implicit as: ActorSystem[Nothing]): Task[CassandraSession] =
    Task.delay {
      CassandraSessionRegistry
        .get(as)
        .sessionFor(CassandraSessionSettings("akka.persistence.cassandra"))
    }

  /**
    * Creates a cassandra configuration for the given configuration
    * @param config the cassandra configuration
    */
  def apply[A: Encoder: Decoder](
      config: CassandraConfig,
      empty: => A,
      throwableToString: Throwable => String
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[CassandraProjection[A]] =
    for {
      s <- session
      _ <- new CassandraProjectionInitialization(s, config).initialize()
    } yield new CassandraProjection[A](s, empty, throwableToString, config)

}
