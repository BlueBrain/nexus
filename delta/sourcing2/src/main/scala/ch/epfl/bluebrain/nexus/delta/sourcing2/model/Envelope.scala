package ch.epfl.bluebrain.nexus.delta.sourcing2.model

import ch.epfl.bluebrain.nexus.delta.sourcing2.offset.Offset

import java.time.Instant

/**
  * Wrapper adding metadata for events and states
  */
final case class Envelope[V](tpe: EntityType, id: EntityId, value: V, rev: Int, instant: Instant, offset: Offset)
