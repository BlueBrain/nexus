package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.{NoOffset, Offset, Sequence}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import fs2.Stream
import fs2.concurrent.Queue
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

object DummyHelpers {

  /**
    * Constructs a stream of events from a sequence of envelopes
    */
  def currentEventsFromJournal[E <: Event](
      envelopes: UIO[Seq[Envelope[E]]],
      offset: Offset,
      maxStreamSize: Long
  ): Stream[Task, Envelope[E]] =
    Stream
      .eval(envelopes)
      .flatMap(envelopes => Stream.emits(envelopes))
      .flatMap { envelope =>
        (envelope.offset, offset) match {
          case (Sequence(envelopeOffset), Sequence(requestedOffset)) =>
            if (envelopeOffset <= requestedOffset) Stream.empty.covary[Task]
            else Stream(envelope)
          case (_, NoOffset)                                         => Stream(envelope)
          case (_, other)                                            => Stream.raiseError[Task](new IllegalArgumentException(s"Unknown offset type '$other'"))
        }
      }
      .take(maxStreamSize)

  /**
    * Constructs a stream of events from a sequence of envelopes
    */
  def eventsFromJournal[E <: Event](
      envelopes: UIO[Seq[Envelope[E]]],
      offset: Offset,
      maxStreamSize: Long
  ): Stream[Task, Envelope[E]] = {
    def addNotSeen(queue: Queue[Task, Envelope[E]], seenCount: Int): Task[Unit] = {
      envelopes.flatMap { envelopes =>
        val delta = envelopes.drop(seenCount)
        if (delta.isEmpty) Task.sleep(10.milliseconds) >> addNotSeen(queue, seenCount)
        else queue.offer1(delta.head) >> addNotSeen(queue, seenCount + 1)
      }
    }

    val streamF = for {
      queue <- Queue.unbounded[Task, Envelope[E]]
      fiber <- addNotSeen(queue, 0).start
      stream = queue.dequeue.onFinalize(fiber.cancel)
    } yield stream

    val stream = Stream.eval(streamF).flatten
    stream
      .flatMap { envelope =>
        (envelope.offset, offset) match {
          case (Sequence(envelopeOffset), Sequence(requestedOffset)) =>
            if (envelopeOffset <= requestedOffset) Stream.empty.covary[Task]
            else Stream(envelope)
          case (_, NoOffset)                                         => Stream(envelope)
          case (_, other)                                            => Stream.raiseError[Task](new IllegalArgumentException(s"Unknown offset type '$other'"))
        }
      }
      .take(maxStreamSize)
  }

}
