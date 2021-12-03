package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import fs2.Stream
import monix.bio.{Task, UIO}

object DummyHelpers {

  /**
    * Constructs a stream of events from a sequence of envelopes
    */
  def currentEventsFromJournal[E <: Event](
      envelopes: UIO[Seq[Envelope[E]]],
      offset: Offset
  ): Stream[Task, Envelope[E]] =
    Stream
      .evalSeq(envelopes)
      .filter { envelope =>
        (envelope.offset, offset) match {
          case (Sequence(envelopeOffset), Sequence(requestedOffset)) => envelopeOffset > requestedOffset
          case _                                                     => true
        }
      }

  /**
    * Constructs a stream of events from a sequence of envelopes
    */
  def eventsFromJournal[E <: Event](
      envelopes: Seq[Envelope[E]],
      offset: Offset
  ): Stream[Task, Envelope[E]] =
    eventsFromJournal(UIO.pure(envelopes), offset)

  /**
    * Constructs a stream of events from a sequence of envelopes
    */
  def eventsFromJournal[E <: Event](
      envelopes: UIO[Seq[Envelope[E]]],
      offset: Offset
  ): Stream[Task, Envelope[E]] = currentEventsFromJournal(envelopes, offset)
}
