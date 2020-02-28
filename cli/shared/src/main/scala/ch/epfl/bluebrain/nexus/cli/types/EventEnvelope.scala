package ch.epfl.bluebrain.nexus.cli.types

/**
 * Event wrapper adding the offset metadata.
 **/
final case class EventEnvelope(offset: Offset, event: Event)
