package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.sourcing.projections.SuccessMessage

/**
  * A typed event envelope.
  *
  * @param event         the event
  * @param eventType     the event qualifier
  * @param offset        the event offset
  * @param persistenceId the event persistence id
  * @param sequenceNr    the event sequence number
  * @param timestamp     the event timestamp
  */
final case class Envelope[E <: Event](
    event: E,
    eventType: String,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    timestamp: Long
) {

  /**
    * Converts the current envelope to a [[SuccessMessage]]
    */
  def toMessage: SuccessMessage[E] = SuccessMessage(offset, persistenceId, sequenceNr, event, Vector.empty)
}
