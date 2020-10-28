package ch.epfl.bluebrain.nexus.delta.service.syntax

import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.{IO, UIO}

trait EventLogSyntax {
  implicit final def eventLogSyntax[State, E <: Event](eventLog: EventLog[Envelope[E]]): EventLogOpts[State, E] =
    new EventLogOpts(eventLog)
}

/**
  * Provide extension methods for an EventLog of Envelope[Event]
  *
  * @param eventLog the eventLog
  */
final class EventLogOpts[State, E <: Event](private val eventLog: EventLog[Envelope[E]]) extends AnyVal {

  /**
    * Compute the state at the given revision
    */
  def fetchStateAt(persistenceId: String, rev: Long, initialState: State, next: (State, E) => State)(implicit
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
        .hideErrors
        .flatMap {
          case Some(state) if revLens.get(state) == rev => UIO.pure(state)
          case Some(`initialState`)                     => IO.pure(initialState)
          case Some(state)                              => IO.raiseError(revLens.get(state))
          case None                                     => IO.raiseError(0L)
        }

}
