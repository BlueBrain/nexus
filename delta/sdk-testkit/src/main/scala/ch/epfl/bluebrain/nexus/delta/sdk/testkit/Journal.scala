package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.concurrent.atomic.AtomicLong

import akka.persistence.query.{NoOffset, Offset, Sequence}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Cache implementation for dummies
  * @param events     storage of events
  * @param semaphore  a semaphore for serializing write operations on the journal
  * @param entityType the entity type
  */
private[testkit] class Journal[Id, E <: Event] private (
    events: IORef[Vector[Envelope[E]]],
    semaphore: IOSemaphore,
    entityType: String
)(implicit idLens: Lens[E, Id]) {

  private val offsetMax = new AtomicLong()

  /**
    * The events groups by id
    */
  def asMap: UIO[Map[Id, Vector[Envelope[E]]]] =
    events.get.map { v =>
      v.groupBy { e => idLens.get(e.event) }.map { case (k, v) =>
        k -> v.sortBy(_.sequenceNr)
      }
    }

  /**
    * Add an event to the journal
    */
  def add(event: E): UIO[Unit] = semaphore.withPermit {
    events.update(e => e :+ makeEnvelope(event))
  }

  private def makeEnvelope(event: E): Envelope[E] = {
    Envelope(
      event,
      event.getClass.getSimpleName,
      Sequence(offsetMax.incrementAndGet()),
      s"$entityType-${idLens.get(event)}",
      event.rev,
      event.instant.toEpochMilli
    )
  }

  private def maxStreamSize(offset: Offset) =
    offset match {
      case NoOffset         => offsetMax.get
      case Sequence(offset) => offsetMax.get - offset
      case _                => throw new IllegalStateException("Only sequence offset is supported in this implementation")
    }

  /**
    * Return the events as a stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[E]] =
    DummyHelpers.eventsFromJournal(
      events.get,
      offset,
      maxStreamSize(offset)
    )

  /**
    * Return the current events as a stream
    */
  def currentEvents(offset: Offset): Stream[Task, Envelope[E]] =
    DummyHelpers.currentEventsFromJournal(
      events.get,
      offset,
      maxStreamSize(offset)
    )

  /**
    * Compute the current state
    */
  def currentState[State](id: Id, initial: State, next: (State, E) => State): UIO[Option[State]] = {
    asMap.map { idsEvents =>
      idsEvents
        .get(id)
        .map(_.foldLeft[State](initial) { (s, e) => next(s, e.event) })
    }
  }

  /**
    * Try to compute the state at the given revision
    * A revision not found is returned if the revision is out of bounds
    */
  def stateAt[State, Rejection](
      id: Id,
      rev: Long,
      initial: State,
      next: (State, E) => State,
      revisionNotFound: (Long, Long) => Rejection
  ): IO[Rejection, Option[State]] =
    asMap.flatMap { labelsEvents =>
      labelsEvents.get(id).traverse { events =>
        if (events.size < rev)
          IO.raiseError(revisionNotFound(rev, events.size.toLong))
        else
          events
            .foldLeft[State](initial) {
              case (state, envelope) if envelope.event.rev <= rev => next(state, envelope.event)
              case (state, _)                                     => state
            }
            .pure[UIO]
      }
    }
}

object Journal {

  /**
    * Construct a journal for the entity type with a number of available permits
    * @param entityType type of entity
    * @param permits    number of permits
    * @param idLens     how to extract the id out of the event
    */
  def apply[Id, E <: Event](entityType: String, permits: Long = 1L)(implicit idLens: Lens[E, Id]): UIO[Journal[Id, E]] =
    for {
      j <- IORef.of[Vector[Envelope[E]]](Vector.empty)
      s <- IOSemaphore(permits)
    } yield new Journal[Id, E](
      j,
      s,
      entityType
    )
}
