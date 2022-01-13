package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventSerializer
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateSerializer

final case class EntitySerializer[Event, State](
    eventSerializer: EventSerializer[Event],
    eventDecoder: PayloadDecoder[Event],
    stateSerializer: StateSerializer[State],
    stateDecoder: PayloadDecoder[State]
)
