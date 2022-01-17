package ch.epfl.bluebrain.nexus.delta.sourcing2

import ch.epfl.bluebrain.nexus.delta.sourcing2.decoder.PayloadDecoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.event.EventEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing2.state.StateEncoder

final case class EntitySerializer[Event, State](
                                                 eventEncoder: EventEncoder[Event],
                                                 eventDecoder: PayloadDecoder[Event],
                                                 stateSerializer: StateEncoder[State],
                                                 stateDecoder: PayloadDecoder[State]
)
