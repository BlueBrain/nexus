package ch.epfl.bluebrain.nexus.delta.sdk.eventlog

import akka.actor.typed.ActorSystem
import akka.persistence.query.{EventEnvelope, Offset}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassUtils
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import ch.epfl.bluebrain.nexus.sourcing.config.DatabaseFlavour
import com.typesafe.scalalogging.Logger
import monix.bio.{IO, Task, UIO}

import scala.reflect.ClassTag

object EventLogUtils {

  private val logger: Logger = Logger("EventLog")

  /**
    * Attempts to convert a generic event envelope to a type one.
    * @param envelope the generic event envelope
    */
  def toEnvelope[E <: Event](envelope: EventEnvelope)(implicit Event: ClassTag[E]): UIO[Option[Envelope[E]]] =
    envelope match {
      case ee @ EventEnvelope(offset: Offset, persistenceId, sequenceNr, Event(value)) =>
        UIO.pure(Some(Envelope(value, ClassUtils.simpleName(value), offset, persistenceId, sequenceNr, ee.timestamp)))
      case _                                                                           =>
        UIO(
          logger.warn(
            s"Failed to match envelope value '${envelope.event}' to class '${Event.runtimeClass.getCanonicalName}'"
          )
        ) >> UIO.pure(None)
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
        .hideErrors
        .flatMap {
          case Some(state) if revLens.get(state) == rev => UIO.pure(state)
          case Some(`initialState`)                     => IO.pure(initialState)
          case Some(state)                              => IO.raiseError(revLens.get(state))
          case None                                     => IO.raiseError(0L)
        }

  /**
    * Wires the [[EventLog]] for the database activated in the configuration for the passed [[Event]] type
    *
    * @param flavour the database flavour
    * @tparam A the event type
    */
  def databaseEventLog[A <: Event: ClassTag](
      flavour: DatabaseFlavour,
      as: ActorSystem[Nothing]
  ): Task[EventLog[Envelope[A]]] =
    flavour match {
      case DatabaseFlavour.Postgres  => EventLog.postgresEventLog(toEnvelope[A])(as)
      case DatabaseFlavour.Cassandra => EventLog.cassandraEventLog(toEnvelope[A])(as)
    }
}
