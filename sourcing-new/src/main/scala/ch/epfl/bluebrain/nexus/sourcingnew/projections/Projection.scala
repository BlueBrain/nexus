package ch.epfl.bluebrain.nexus.sourcingnew.projections

import akka.persistence.query.Offset
import cats.effect.{ContextShift, IO, LiftIO}
import fs2.Stream

import scala.concurrent.Future

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
  def failures(id: String):Stream[F, (A, Offset)]
}

object Projection {

  private [projections] def wrapFuture[F[_]: LiftIO, A](f: => Future[A])
                                                       (implicit cs: ContextShift[IO]): F[A] =
    IO.fromFuture(IO(f)).to[F]
}
