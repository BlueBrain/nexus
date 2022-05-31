package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset

import java.time.Instant

/**
  * Envelope adding metadata along the event/state
  * @param tpe
  *   the entity type
  * @param id
  *   the identifier
  * @param rev
  *   the revision
  * @param value
  *   the event/state
  * @param instant
  *   the instant
  * @param offset
  *   the offset
  */
final case class Envelope[Id, Value](tpe: EntityType, id: Id, rev: Int, value: Value, instant: Instant, offset: Offset)
