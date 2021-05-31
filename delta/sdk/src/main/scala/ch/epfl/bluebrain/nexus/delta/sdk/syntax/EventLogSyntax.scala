package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.kernel.{Lens, Mapper}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import monix.bio.{IO, Task}
import fs2.Stream

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

  /**
    * Fetch project events
    */
  def projectEvents[R](projects: Projects, projectRef: ProjectRef, offset: Offset)(implicit
      rejectionMapper: Mapper[ProjectNotFound, R]
  ): IO[R, Stream[Task, Envelope[E]]] =
    EventLogUtils.projectEvents(projects, eventLog, projectRef, offset)

  /**
    * Fetch organization events
    */
  def orgEvents[R](orgs: Organizations, label: Label, offset: Offset)(implicit
      rejectionMapper: Mapper[OrganizationRejection, R]
  ): IO[R, Stream[Task, Envelope[E]]] =
    EventLogUtils.orgEvents(orgs, eventLog, label, offset)
}
