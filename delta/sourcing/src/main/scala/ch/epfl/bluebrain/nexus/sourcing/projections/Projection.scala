package ch.epfl.bluebrain.nexus.sourcing.projections

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.sourcing.config.{CassandraConfig, PostgresConfig}
import ch.epfl.bluebrain.nexus.sourcing.projections.cassandra.CassandraProjection
import ch.epfl.bluebrain.nexus.sourcing.projections.postgres.PostgresProjection
import fs2.Stream
import io.circe.{Decoder, Encoder}
import monix.bio.{Task, UIO}

import java.io.{PrintWriter, StringWriter}

/**
  * A Projection represents the process to transforming an event stream into a format that's efficient for consumption.
  * In terms of CQRS, the events represents the format in which data is written to the primary store (the write
  * model) while the result of a projection represents the data in a consumable format (the read model).
  *
  * Projections replay an event stream
  */
trait Projection[A] {

  /**
    * Records progress against a projection identifier.
    *
    * @param id       the projection identifier
    * @param progress the offset to record
    * @return a future () value
    */
  def recordProgress(id: ProjectionId, progress: ProjectionProgress): Task[Unit]

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  def progress(id: ProjectionId): Task[ProjectionProgress]

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id             the project identifier
    * @param errorMessage the error message to persist
    */
  def recordFailure(
      id: ProjectionId,
      errorMessage: ErrorMessage,
      f: Throwable => String = Projection.stackTraceAsString
  ): Task[Unit]

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  def failures(id: ProjectionId): Stream[Task, ProjectionFailure[A]]
}

object Projection {

  /**
    * Allows to get a readable stacktrace
    * @param t the exception to format
    * @return the stacktrace formatted in an human readable fashion
    */
  def stackTraceAsString(t: Throwable): String = {
    val sw = new StringWriter
    t.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  /**
    * Create a projection for Cassandra
    */
  def cassandra[A: Encoder: Decoder](
      config: CassandraConfig
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[Projection[A]] =
    CassandraProjection.apply(config)

  /**
    * Create a projection for PostgreSQL
    */
  def postgres[A: Encoder: Decoder](postgresConfig: PostgresConfig)(implicit clock: Clock[UIO]): Task[Projection[A]] =
    Task.delay {
      new PostgresProjection[A](postgresConfig.transactor)
    }
}
