package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.actor.typed.ActorSystem
import akka.persistence.query.{EventEnvelope, Offset, TimeBasedUUID}
import ch.epfl.bluebrain.nexus.delta.kernel.{Lens, Mapper}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.{EventLog, OffsetUtils}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour
import ch.epfl.bluebrain.nexus.delta.sourcing.config.DatabaseFlavour.{Cassandra, Postgres}
import com.datastax.oss.driver.api.core.uuid.Uuids
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}

import java.time.Instant
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

  /**
    * Wires the [[EventLog]] for the database activated in the configuration for the passed [[Event]] type
    *
    * @param flavour
    *   the database flavour
    * @tparam A
    *   the event type
    */
  def databaseEventLog[A <: Event: ClassTag](
      flavour: DatabaseFlavour,
      as: ActorSystem[Nothing]
  ): Task[EventLog[Envelope[A]]] =
    flavour match {
      case DatabaseFlavour.Postgres  => EventLog.postgresEventLog(toEnvelope[A])(as)
      case DatabaseFlavour.Cassandra => EventLog.cassandraEventLog(toEnvelope[A])(as)
    }

  /**
    * Fetch events related to the given project
    * @param projects
    *   a [[Projects]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param projectRef
    *   the project ref where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def projectEvents[R, M](projects: Projects, eventLog: EventLog[M], projectRef: ProjectRef, offset: Offset)(implicit
      rejectionMapper: Mapper[ProjectNotFound, R]
  ): IO[R, fs2.Stream[Task, M]] =
    projects
      .fetch(projectRef)
      .bimap(
        rejectionMapper.to,
        p => events(eventLog, Projects.projectTag(projectRef), p.createdAt, offset)
      )

  /**
    * Fetch events related to the given project
    * @param projects
    *   a [[Projects]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param projectRef
    *   the project ref where the events belong
    * @param module
    *   the module ref where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def projectEvents[R, M](
      projects: Projects,
      eventLog: EventLog[M],
      projectRef: ProjectRef,
      module: String,
      offset: Offset
  )(implicit
      rejectionMapper: Mapper[ProjectNotFound, R]
  ): IO[R, fs2.Stream[Task, M]] =
    projects
      .fetch(projectRef)
      .bimap(
        rejectionMapper.to,
        p => events(eventLog, Projects.projectTag(module, projectRef), p.createdAt, offset)
      )

  /**
    * Fetch current events related to the given project
    * @param projects
    *   a [[Projects]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param projectRef
    *   the project ref where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def currentProjectEvents[R, M](projects: Projects, eventLog: EventLog[M], projectRef: ProjectRef, offset: Offset)(
      implicit rejectionMapper: Mapper[ProjectNotFound, R]
  ): IO[R, fs2.Stream[Task, M]] =
    projects
      .fetch(projectRef)
      .bimap(
        rejectionMapper.to,
        p => currentEvents(eventLog, Projects.projectTag(projectRef), p.createdAt, offset)
      )

  /**
    * Fetch current events related to the given project and module
    * @param projects
    *   a [[Projects]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param projectRef
    *   the project ref where the events belong
    * @param module
    *   the project ref where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def currentProjectEvents[R, M](
      projects: Projects,
      eventLog: EventLog[M],
      projectRef: ProjectRef,
      module: String,
      offset: Offset
  )(implicit
      rejectionMapper: Mapper[ProjectNotFound, R]
  ): IO[R, fs2.Stream[Task, M]] =
    projects
      .fetch(projectRef)
      .bimap(
        rejectionMapper.to,
        p => currentEvents(eventLog, Projects.projectTag(module, projectRef), p.createdAt, offset)
      )

  /**
    * Fetch events related to the given project
    * @param orgs
    *   a [[Organizations]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param label
    *   the project ref where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def orgEvents[R, M](orgs: Organizations, eventLog: EventLog[M], label: Label, offset: Offset)(implicit
      rejectionMapper: Mapper[OrganizationRejection, R]
  ): IO[R, fs2.Stream[Task, M]] =
    orgs
      .fetch(label)
      .bimap(
        rejectionMapper.to,
        o => events(eventLog, Organizations.orgTag(label), o.createdAt, offset)
      )

  /**
    * Fetch events related to the given project for the selected module
    * @param orgs
    *   a [[Organizations]] instance
    * @param eventLog
    *   a [[EventLog]] instance
    * @param label
    *   the project ref where the events belong
    * @param module
    *   the module where the events belong
    * @param offset
    *   the requested offset
    * @param rejectionMapper
    *   to fit the project rejection to one handled by the caller
    */
  def orgEvents[R, M](orgs: Organizations, eventLog: EventLog[M], label: Label, module: String, offset: Offset)(implicit
      rejectionMapper: Mapper[OrganizationRejection, R]
  ): IO[R, fs2.Stream[Task, M]] =
    orgs
      .fetch(label)
      .bimap(
        rejectionMapper.to,
        o => events(eventLog, Organizations.orgTag(module, label), o.createdAt, offset)
      )

  private def events[M](eventLog: EventLog[M], tag: String, instant: Instant, offset: Offset) =
    eventLog.config.flavour match {
      case Postgres  => eventLog.eventsByTag(tag, offset)
      case Cassandra =>
        val maxOffset = OffsetUtils.offsetOrdering.max(
          offset,
          OffsetUtils.offsetOrdering
            .max(eventLog.config.firstOffset, TimeBasedUUID(Uuids.startOf(instant.toEpochMilli)))
        )
        eventLog.eventsByTag(tag, maxOffset)
    }

  private def currentEvents[M](eventLog: EventLog[M], tag: String, instant: Instant, offset: Offset) =
    eventLog.config.flavour match {
      case Postgres  => eventLog.currentEventsByTag(tag, offset)
      case Cassandra =>
        val maxOffset = OffsetUtils.offsetOrdering.max(
          offset,
          OffsetUtils.offsetOrdering
            .max(eventLog.config.firstOffset, TimeBasedUUID(Uuids.startOf(instant.toEpochMilli)))
        )
        eventLog.currentEventsByTag(tag, maxOffset)
    }
}
