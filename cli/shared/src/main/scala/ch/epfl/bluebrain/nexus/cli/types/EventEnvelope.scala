package ch.epfl.bluebrain.nexus.cli.types

/**
 * Event wrapper adding its metadata.
 *
 * @param offset the offset of the event
 * @param event  the event value
 **/
final case class EventEnvelope(offset: Offset, event: Event)
