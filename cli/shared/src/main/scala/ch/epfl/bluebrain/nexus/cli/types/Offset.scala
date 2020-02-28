package ch.epfl.bluebrain.nexus.cli.types

import java.util.UUID

/**
  * An offset for events.
  */
sealed trait Offset extends Product with Serializable

object Offset {

  final implicit val offsetOrdering: Ordering[Offset] = {
    case (x: Sequence, y: Sequence)           => x compareTo y
    case (x: TimeBasedUUID, y: TimeBasedUUID) => x.value.timestamp() compareTo y.value.timestamp()
    case _                                    => 0
  }

  /**
    * Corresponds to an ordered unique identifier of the events. Note that the corresponding
    * offset of each event is provided in the [[EventEnvelope]],
    * which makes it possible to resume the stream at a later point from a given offset.
    *
    * The `offset` is exclusive, i.e. the event with the exact same sequence number will not be included
    * in the returned stream. This means that you can use the offset that is returned in `EventEnvelope`
    * as the `offset` parameter in a subsequent query.
    */
  final case class TimeBasedUUID(value: UUID) extends Offset with Ordered[TimeBasedUUID] {
    override def compare(that: TimeBasedUUID): Int = value.timestamp() compareTo that.value.timestamp()
  }

  /**
    * Corresponds to an ordered sequence number for the events. Note that the corresponding
    * offset of each event is provided in the [[EventEnvelope]],
    * which makes it possible to resume the stream at a later point from a given offset.
    *
    * The `offset` is exclusive, i.e. the event with the exact same sequence number will not be included
    * in the returned stream. This means that you can use the offset that is returned in `EventEnvelope`
    * as the `offset` parameter in a subsequent query.
    */
  final case class Sequence(value: Long) extends Offset with Ordered[Sequence] {
    override def compare(that: Sequence): Int = value compareTo that.value
  }

}
