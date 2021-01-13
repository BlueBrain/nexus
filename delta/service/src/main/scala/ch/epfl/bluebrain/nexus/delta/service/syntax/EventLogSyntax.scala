package ch.epfl.bluebrain.nexus.delta.service.syntax

import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import monix.bio.IO

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
    EventLogUtils.fetchStateAt(eventLog, persistenceId, rev, initialState, next)

}
