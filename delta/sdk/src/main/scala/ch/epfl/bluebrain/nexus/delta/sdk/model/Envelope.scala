package ch.epfl.bluebrain.nexus.delta.sdk.model

import akka.persistence.query.Offset

import scala.reflect.ClassTag

/**
  * A typed event envelope.
  *
  * @param event         the event
  * @param offset        the event offset
  * @param persistenceId the event persistence id
  * @param sequenceNr    the event sequence number
  * @param timestamp     the event timestamp
  */
final case class Envelope[E <: Event: ClassTag](
    event: E,
    offset: Offset,
    persistenceId: String,
    sequenceNr: Long,
    timestamp: Long
) {

  /**
    * The event type.
    */
  lazy val eventType: String = implicitly[ClassTag[E]].runtimeClass.getName
}
