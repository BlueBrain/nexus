package ch.epfl.bluebrain.nexus.delta.sourcing.serialization

import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event
import ch.epfl.bluebrain.nexus.delta.sourcing.model.EntityType
import io.circe.Encoder

final case class ValueEncoder[Value](
    entityType: EntityType,
    serializeId: Event => String,
    encoder: Encoder.AsObject[Value]
)
