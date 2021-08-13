package ch.epfl.bluebrain.nexus.delta.sourcing.projections

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.{CassandraConfig, DatabaseConfig, PostgresConfig}
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.cassandra.CassandraProjection
import ch.epfl.bluebrain.nexus.delta.sourcing.projections.postgres.PostgresProjection
import fs2.Stream
import io.circe.{Decoder, Encoder}
import monix.bio.{Task, UIO}

import java.io.{PrintWriter, StringWriter}
import scala.collection.concurrent.TrieMap

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
  def recordProgress(id: ProjectionId, progress: ProjectionProgress[A]): Task[Unit]

  /**
    * Retrieves the progress for the specified projection projectionId. If there is no record of progress
    * the [[ProjectionProgress.NoProgress]] is returned.
    *
    * @param id an unique projectionId for a projection
    * @return a future progress value for the specified projection projectionId
    */
  def progress(id: ProjectionId): Task[ProjectionProgress[A]]

  /**
    * Record a specific event against a index failures log projectionId.
    *
    * @param id       the projection identifier
    * @param messages the messages with potentially errors to save
    */
  def recordErrors(
      id: ProjectionId,
      messages: Vector[Message[A]]
  ): Task[Unit]

  /**
    * An event stream for all failures recorded for a projection.
    *
    * @param id the projection identifier
    * @return a source of the failed events
    */
  def errors(id: ProjectionId): Stream[Task, ProjectionError[A]]
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
    * Create a projection according to the database configuration
    */
  def apply[A: Decoder: Encoder](
      config: DatabaseConfig,
      empty: => A,
      system: ActorSystem[Nothing],
      clock: Clock[UIO]
  ): Task[Projection[A]] = {
    implicit val as: ActorSystem[Nothing] = system
    implicit val c: Clock[UIO]            = clock
    config.flavour match {
      case Postgres  => Projection.postgres(config.postgres, empty)
      case Cassandra => Projection.cassandra(config.cassandra, empty)
    }
  }

  /**
    * Create a projection for Cassandra
    */
  def cassandra[A: Encoder: Decoder](
      config: CassandraConfig,
      empty: => A,
      throwableToString: Throwable => String = Projection.stackTraceAsString
  )(implicit as: ActorSystem[Nothing], clock: Clock[UIO]): Task[Projection[A]] =
    CassandraProjection(config, empty, throwableToString)

  /**
    * Create a projection for PostgreSQL
    */
  def postgres[A: Encoder: Decoder](
      postgresConfig: PostgresConfig,
      empty: => A,
      throwableToString: Throwable => String = Projection.stackTraceAsString
  )(implicit clock: Clock[UIO]): Task[Projection[A]] =
    Task.delay(PostgresProjection[A](postgresConfig.transactor, empty, throwableToString))

  /**
    * A Projection that records its progress in memory.
    * This implementation does not survive system restarts.
    */
  def inMemory[A](empty: => A, throwableToString: Throwable => String = Projection.stackTraceAsString)(implicit
      clock: Clock[UIO]
  ): Task[Projection[A]] =
    Task.delay {
      new InMemoryProjection[A](empty, throwableToString, TrieMap.empty, TrieMap.empty)
    }
}
