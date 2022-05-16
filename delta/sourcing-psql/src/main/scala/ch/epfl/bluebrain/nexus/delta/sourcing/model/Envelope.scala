package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset

import java.time.Instant

final case class Envelope[Id, Value](tpe: EntityType, id: Id, rev: Int, value: Value, instant: Instant, offset: Offset)
