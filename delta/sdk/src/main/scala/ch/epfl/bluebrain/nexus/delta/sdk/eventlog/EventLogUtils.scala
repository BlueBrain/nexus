package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.persistence.query.{EventEnvelope, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, UIO}

import scala.reflect.ClassTag

object EventLogUtils {

  implicit private val logger: Logger = Logger("EventLog")

  /**
    * Attempts to convert a generic event envelope to a type one.
    * @param envelope
    *   the generic event envelope
    */
  def toEnvelope[E <: Event](envelope: EventEnvelope)(implicit Event: ClassTag[E]): UIO[Option[Envelope[E]]] =
    envelope match {
      case EventEnvelope(offset: Offset, persistenceId, sequenceNr, Event(value)) =>
        UIO.delay(Some(Envelope(value, offset, persistenceId, sequenceNr)))
      case _                                                                      =>
        // This might be the expected behaviour, in situations where a tag is shared by multiple event types.
        UIO.delay(
          logger.debug(s"Failed to match envelope value '${envelope.event}' to class '${Event.simpleName}'")
        ) >> UIO.none
    }

  /**
    * Compute the state at the given revision from the event log
    */
  def fetchStateAt[State, E <: Event](
      eventLog: EventLog[Envelope[E]],
      persistenceId: String,
      rev: Long,
      initialState: State,
      next: (State, E) => State
  )(implicit
      revLens: Lens[State, Long]
  ): IO[Long, State] =
    if (rev == 0L) UIO.pure(initialState)
    else
      eventLog
        .currentEventsByPersistenceId(
          persistenceId,
          Long.MinValue,
          Long.MaxValue
        )
        .takeWhile(_.event.rev <= rev)
        .fold[State](initialState) { case (state, event) =>
          next(state, event.event)
        }
        .compile
        .last
        .logAndDiscardErrors(s"running stream to compute state from persistenceId '$persistenceId' and rev '$rev'")
        .flatMap {
          case Some(state) if revLens.get(state) == rev => UIO.pure(state)
          case Some(`initialState`)                     => IO.pure(initialState)
          case Some(state)                              => IO.raiseError(revLens.get(state))
          case None                                     => IO.raiseError(0L)
        }
}
