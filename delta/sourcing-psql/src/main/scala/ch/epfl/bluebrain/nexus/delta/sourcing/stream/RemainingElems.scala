package ch.epfl.bluebrain.nexus.delta.sourcing.stream

import java.time.Instant

/**
  * Describes the remaining elements to stream
  * @param count
  *   the number of remaining elements
  * @param maxInstant
  *   the instant of the last element
  */
final case class RemainingElems(count: Long, maxInstant: Instant)
