package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

/**
  * Super type of all events.
  */
trait Event extends Product with Serializable {

  /**
    * @return the revision this events generates
    */
  def rev: Long

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject
}
