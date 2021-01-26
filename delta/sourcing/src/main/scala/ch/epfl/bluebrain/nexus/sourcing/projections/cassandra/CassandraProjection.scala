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
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
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
private[projections] class CassandraProjection[A: Encoder: Decoder](
    session: CassandraSession,
    config: CassandraConfig
)(implicit as: ActorSystem[Nothing], clock: Clock[UIO])
    extends Projection[A] {

  implicit private val materializer: Materializer = Materializer.createMaterializer(as)

  val recordProgressQuery: String =
    s"UPDATE ${config.keyspace}.projections_progress SET offset = ?, timestamp = ?, processed = ?, discarded = ?, failed = ? WHERE projection_id = ?"

  val progressQuery: String =
    s"SELECT offset, timestamp, processed, discarded, failed FROM ${config.keyspace}.projections_progress WHERE projection_id = ?"

  val recordFailureQuery: String =
    s"""INSERT INTO ${config.keyspace}.projections_failures (projection_id, offset, timestamp, persistence_id, sequence_nr, value, error_type, error)
       |VALUES (?, ?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  val failuresQuery: String =
    s"SELECT offset, timestamp, value, error_type from ${config.keyspace}.projections_failures WHERE projection_id = ? ALLOW FILTERING"

  private def offsetToUUID(offset: Offset): Option[UUID] =
    offset match {
      case TimeBasedUUID(uuid) => Some(uuid)
      case _                   => None
    }

  private def parseOffset(value: UUID): Offset = Option(value).fold[Offset](NoOffset)(TimeBasedUUID)

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): Task[Unit] =
    Task.deferFuture {
      logger.info(s"Recording projection progress {}} at offset {}", id, progress.offset)
      session.executeWrite(
        recordProgressQuery,
        offsetToUUID(progress.offset).orNull,
        progress.timestamp.toEpochMilli: java.lang.Long,
        progress.processed: java.lang.Long,
        progress.discarded: java.lang.Long,
        progress.failed: java.lang.Long,
        id.value
      )
    } >> Task.unit

  override def progress(id: ProjectionId): Task[ProjectionProgress] =
    Task.deferFuture(session.selectOne(progressQuery, id.value)).map {
      _.fold(NoProgress) { row =>
        ProjectionProgress(
          parseOffset(row.getUuid("offset")),
          Instant.ofEpochMilli(row.getLong("timestamp")),
          row.getLong("processed"),
          row.getLong("discarded"),
          row.getLong("failed")
        )
      }
    }

  override def recordFailure(
      id: ProjectionId,
      errorMessage: ErrorMessage,
      f: Throwable => String = Projection.stackTraceAsString
  ): Task[Unit] = {
    logger.error(s"Recording error during projection {} at offset {}", id, errorMessage.offset)
    def errorWrite(instant: Instant) = errorMessage match {
      case CastFailedMessage(offset, persistenceId, sequenceNr, expectedClassname, encounteredClassName) =>
        val error = s"Class $expectedClassname was expected, $encounteredClassName was encountered "
        Task.deferFuture(
          session.executeWrite(
            recordFailureQuery,
            id.value,
            offsetToUUID(offset).orNull,
            instant.toEpochMilli: java.lang.Long,
            persistenceId,
            sequenceNr: java.lang.Long,
            null,
            ClassUtils.simpleName(errorMessage),
            error
          )
        ) >> Task.unit
      case failureMessage: FailureMessage[A]                                                             =>
        Task.deferFuture(
          session.executeWrite(
            recordFailureQuery,
            id.value,
            offsetToUUID(failureMessage.offset).orNull,
            instant.toEpochMilli: java.lang.Long,
            failureMessage.persistenceId,
            failureMessage.sequenceNr: java.lang.Long,
            failureMessage.value.asJson.noSpaces,
            ClassUtils.simpleName(failureMessage.throwable),
            f(failureMessage.throwable)
          )
        )
    }

    for {
      timestamp <- instant
      _         <- errorWrite(timestamp)
    } yield ()
  }

  override def failures(id: ProjectionId): Stream[Task, ProjectionFailure[A]] =
    session
      .select(failuresQuery, id.value)
      .toStream[Task](_ => ())
      .map { row =>
        ProjectionFailure(
          parseOffset(row.getUuid("offset")),
          Instant.ofEpochMilli(row.getLong("timestamp")),
          Option(row.getString("value")).flatMap(decode[A](_).toOption),
          row.getString("error_type")
        )
      }
}

object CassandraProjection {

  private val logger: Logger = Logger[CassandraProjection.type]

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
      config: CassandraConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[CassandraProjection[A]] = session.map {
    new CassandraProjection[A](
      _,
      config
    )
  }
}
