package ch.epfl.bluebrain.nexus.delta.sdk.model

final case class Envelope[E <: Event, Offset](
    event: E,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    timestamp: Long
)
