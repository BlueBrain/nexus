package ch.epfl.bluebrain.nexus.cli.sse

import cats.effect.concurrent.Ref
import ch.epfl.bluebrain.nexus.cli.{ClientErrOffsetOr, LabeledEvent}
import fs2.Stream

/**
  * A stream of events with an optional offset.
  */
trait EventStream[F[_]] {

  /**
    * The Stream of events.
    */
  def value: F[Stream[F, ClientErrOffsetOr[LabeledEvent]]]

  /**
    * The eventId for the last consumed event.
    */
  def currentEventId(): F[Option[Offset]]
}

object EventStream {
  final def apply[F[_]](
      stream: F[Stream[F, ClientErrOffsetOr[LabeledEvent]]],
      ref: Ref[F, Option[Offset]]
  ): EventStream[F] =
    new EventStream[F] {
      override def value: F[Stream[F, ClientErrOffsetOr[LabeledEvent]]] = stream
      override def currentEventId(): F[Option[Offset]]                  = ref.get
    }
}
