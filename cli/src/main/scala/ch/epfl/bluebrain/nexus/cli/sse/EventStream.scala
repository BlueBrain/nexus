package ch.epfl.bluebrain.nexus.cli.sse

import cats.effect.concurrent.Ref
import fs2.Stream

/**
  * A stream of events with an optional offset.
  */
trait EventStream[F[_]] {

  /**
    * The Stream of events.
    */
  def value: Stream[F, Event]

  /**
    * The eventId for the last consumed event.
    */
  def currentEventId(): F[Option[Offset]]
}

object EventStream {
  final def apply[F[_]](stream: Stream[F, Event], ref: Ref[F, Option[Offset]]): EventStream[F] = new EventStream[F] {
    override def value: Stream[F, Event]             = stream
    override def currentEventId(): F[Option[Offset]] = ref.get
  }
}
