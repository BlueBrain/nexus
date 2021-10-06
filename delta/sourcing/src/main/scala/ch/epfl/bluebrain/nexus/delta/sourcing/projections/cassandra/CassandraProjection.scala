package ch.epfl.bluebrain.nexus.delta.sourcing.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.persistence.query.{NoOffset, Offset, TimeBasedUUID}
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionError.{ProjectionFailure, ProjectionWarning}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.Severity.{Failure, Warning}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections._
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.cassandra.CassandraProjection._
import ch.epfl.bluebrain.nexus.delta.sourcing.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.utils.CassandraUtils
import com.datastax.oss.driver.api.core.cql.{BatchStatement, BatchType, PreparedStatement, Row}
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

  private val deleteProgressQuery: String =
    s"DELETE FROM ${config.keyspace}.projections_progress WHERE projection_id = ? IF EXISTS"

  private val deleteProgressErrors: String =
    s"DELETE FROM ${config.keyspace}.projections_errors WHERE projection_id = ? and timestamp = ? and persistence_id = ? and sequence_nr = ? IF EXISTS"

  private val recordProgressQuery: String =
    s"UPDATE ${config.keyspace}.projections_progress SET offset = ?, timestamp = ?, processed = ?, discarded = ?, warnings = ?, failed = ?, value = ?, value_timestamp = ? WHERE projection_id = ?"

  private val progressQuery: String =
    s"SELECT offset, value_timestamp, processed, discarded, warnings, failed, value FROM ${config.keyspace}.projections_progress WHERE projection_id = ?"

  private val recordErrorQuery: String =
    s"""INSERT INTO ${config.keyspace}.projections_errors (projection_id, offset, timestamp, persistence_id, sequence_nr, value, value_timestamp, severity, error_type, message)
       |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  private val failuresQuery: String =
    s"SELECT offset, timestamp, persistence_id, sequence_nr, value, value_timestamp, severity,  error_type, message from ${config.keyspace}.projections_errors WHERE projection_id = ?"

  private def offsetToUUID(offset: Offset): Option[UUID] =
    offset match {
      case TimeBasedUUID(uuid) => Some(uuid)
      case _                   => None
    }

  private def parseOffset(value: UUID): Offset = Option(value).fold[Offset](NoOffset)(TimeBasedUUID)

  override def delete(id: ProjectionId): Task[Unit] =
    Task.deferFuture {
      session.executeWrite(deleteProgressQuery, id.value)
    } >> errors(id)
      .evalMap { error =>
        Task.deferFuture {
          session.executeWrite(
            deleteProgressErrors,
            id.value,
            error.timestamp.toEpochMilli: java.lang.Long,
            error.persistenceId,
            error.sequenceNr: java.lang.Long
          )
        }
      }
      .compile
      .drain

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit] =
    instant.flatMap { timestamp =>
      Task.deferFuture {
        logger.debug(s"Recording projection progress '$id' at offset '${progress.offset.asString}'")
        session.executeWrite(
          recordProgressQuery,
          offsetToUUID(progress.offset).orNull,
          timestamp.toEpochMilli: java.lang.Long,
          progress.processed: java.lang.Long,
          progress.discarded: java.lang.Long,
          progress.warnings: java.lang.Long,
          progress.failed: java.lang.Long,
          progress.value.asJson.noSpaces,
          progress.timestamp.toEpochMilli: java.lang.Long,
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
            Instant.ofEpochMilli(row.getLong("value_timestamp")),
            row.getLong("processed"),
            row.getLong("discarded"),
            row.getLong("warnings"),
            row.getLong("failed"),
            value
          )
        }
      case None      => Task.pure(NoProgress(empty))
    }

  private def batchErrors(
      id: ProjectionId,
      statement: PreparedStatement,
      timestamp: Instant,
      messages: Vector[Message[A]]
  ) = {
    val statements = messages.mapFilter {
      case c: CastFailedMessage                        =>
        Some(
          statement.bind(
            id.value,
            offsetToUUID(c.offset).orNull,
            timestamp.toEpochMilli: java.lang.Long,
            c.persistenceId,
            c.sequenceNr: java.lang.Long,
            null: String,
            null: java.lang.Long,
            Severity.Failure.toString,
            "ClassCastException",
            c.errorMessage
          )
        )
      case f: FailureMessage[A]                        =>
        Some(
          statement.bind(
            id.value,
            offsetToUUID(f.offset).orNull,
            timestamp.toEpochMilli: java.lang.Long,
            f.persistenceId,
            f.sequenceNr: java.lang.Long,
            null: String,
            f.timestamp.toEpochMilli: java.lang.Long,
            Severity.Failure.toString,
            ClassUtils.simpleName(f.throwable),
            throwableToString(f.throwable)
          )
        )
      case w: SuccessMessage[A] if w.warnings.nonEmpty =>
        Some(
          statement.bind(
            id.value,
            offsetToUUID(w.offset).orNull,
            timestamp.toEpochMilli: java.lang.Long,
            w.persistenceId,
            w.sequenceNr: java.lang.Long,
            w.value.asJson.noSpaces,
            w.timestamp.toEpochMilli: java.lang.Long,
            Severity.Warning.toString,
            null,
            w.warningMessage
          )
        )
      case _                                           => None
    }

    Option.when(statements.nonEmpty)(BatchStatement.newInstance(BatchType.UNLOGGED, statements: _*))
  }

  override def recordErrors(
      id: ProjectionId,
      messages: Vector[Message[A]]
  ): Task[Unit] = {
    for {
      timestamp <- instant
      statement <- Task.deferFuture(session.prepare(recordErrorQuery))
      batch      = batchErrors(id, statement, timestamp, messages)
      offsets    = messages.map(_.offset.asString)
      w         <-
        batch.fold(Task.unit) { b =>
          Task.deferFuture(session.executeWriteBatch(b)).void >>
            Task.delay(
              logger.error(
                s"Recording '${b.size}' errors during projection '${id.value}' with offsets '${offsets.mkString(",")}'"
              )
            )
        }
    } yield w
  }

  private def timestampValue(row: Row) =
    Option(row.getLong("value_timestamp")).filterNot(_ == 0L).map(Instant.ofEpochMilli)

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
              Option(row.getString("value")).flatMap(decode[A](_).toOption),
              timestampValue(row)
            )
          case Failure =>
            ProjectionFailure(
              parseOffset(row.getUuid("offset")),
              Instant.ofEpochMilli(row.getLong("timestamp")),
              row.getString("message"),
              row.getString("persistence_id"),
              row.getLong("sequence_nr"),
              Option(row.getString("value")).flatMap(decode[A](_).toOption),
              timestampValue(row),
              row.getString("error_type")
            )
        }

      }
}

object CassandraProjection {

  private[cassandra] val logger: Logger = Logger[CassandraProjection.type]

  /**
    * Creates a cassandra projection with the given configuration
    * @param config
    *   the cassandra configuration
    */
  def apply[A: Encoder: Decoder](
      config: CassandraConfig,
      empty: => A,
      throwableToString: Throwable => String
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[CassandraProjection[A]] =
    CassandraUtils.session.map(s => new CassandraProjection[A](s, empty, throwableToString, config))

}
