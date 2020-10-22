package ch.epfl.bluebrain.nexus.sourcing.projections.cassandra

import akka.actor.typed.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.sourcing.config.CassandraConfig
import ch.epfl.bluebrain.nexus.sourcing.projections.ProjectionProgress.NoProgress
import ch.epfl.bluebrain.nexus.sourcing.projections.instances._
import ch.epfl.bluebrain.nexus.sourcing.projections.{FailureMessage, Projection, ProjectionId, ProjectionProgress}
import fs2.Stream
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import monix.bio.Task
import streamz.converter._

/**
  * Implementation of [[Projection]] for Cassandra
  */
private[projections] class CassandraProjection[A: Encoder: Decoder](
    session: CassandraSession,
    config: CassandraConfig,
    as: ActorSystem[Nothing]
) extends Projection[A] {

  implicit private val materializer: Materializer = Materializer.createMaterializer(as)

  val recordProgressQuery: String =
    s"UPDATE ${config.keyspace}.projections_progress SET offset = ?, processed = ?, discarded = ?, failed = ? WHERE projection_id = ?"

  val progressQuery: String =
    s"SELECT offset, processed, discarded, failed FROM ${config.keyspace}.projections_progress WHERE projection_id = ?"

  val recordFailureQuery: String =
    s"""INSERT INTO ${config.keyspace}.projections_failures (projection_id, offset, persistence_id, sequence_nr, value, error_type, error)
       |VALUES (?, ?, ?, ?, ?, ?, ?) IF NOT EXISTS""".stripMargin

  val failuresQuery: String =
    s"SELECT offset, value, error_type from ${config.keyspace}.projections_failures WHERE projection_id = ? ALLOW FILTERING"

  override def recordProgress(id: ProjectionId, progress: ProjectionProgress): Task[Unit] =
    Task.deferFuture(
      session.executeWrite(
        recordProgressQuery,
        progress.offset.asJson.noSpaces,
        progress.processed: java.lang.Long,
        progress.discarded: java.lang.Long,
        progress.failed: java.lang.Long,
        id.value
      )
    ) >> Task.unit

  override def progress(id: ProjectionId): Task[ProjectionProgress] =
    Task.deferFuture(session.selectOne(progressQuery, id.value)).map {
      _.fold(NoProgress) { row =>
        ProjectionProgress.fromTuple(
          (
            row.getString("offset"),
            row.getLong("processed"),
            row.getLong("discarded"),
            row.getLong("failed")
          )
        )
      }
    }

  override def recordFailure(
      id: ProjectionId,
      failureMessage: FailureMessage[A],
      f: Throwable => String = Projection.stackTraceAsString
  ): Task[Unit] =
    Task.deferFuture(
      session.executeWrite(
        recordFailureQuery,
        id.value,
        failureMessage.offset.asJson.noSpaces,
        failureMessage.persistenceId,
        failureMessage.sequenceNr: java.lang.Long,
        failureMessage.value.asJson.noSpaces,
        ClassUtils.simpleName(failureMessage.throwable),
        f(failureMessage.throwable)
      )
    ) >> Task.unit

  override def failures(id: ProjectionId): Stream[Task, (A, Offset, String)] =
    session
      .select(failuresQuery, id.value)
      .toStream[Task](_ => ())
      .mapFilter { row =>
        Projection.decodeError[A, Offset](
          (row.getString("value"), row.getString("offset"), row.getString("error_type"))
        )
      }
}

object CassandraProjection {

  /**
    * @param as the underlying actor system
    * @return a cassandra session from the actor system registry
    */
  def session(as: ActorSystem[Nothing]): Task[CassandraSession] =
    Task.delay {
      CassandraSessionRegistry
        .get(as)
        .sessionFor(CassandraSessionSettings("akka.persistence.cassandra"))
    }
}
