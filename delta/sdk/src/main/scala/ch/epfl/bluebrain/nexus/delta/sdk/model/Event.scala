package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

import java.time.Instant

/**
  * Super type of all events.
  */
sealed trait Event extends Product with Serializable {

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

object Event {

  trait ProjectScopedEvent extends Event {

    /**
      * @return the project where the event belongs
      */
    def project: ProjectRef

  }

  trait OrganizationScopedEvent extends Event {

    /**
      * @return the organization where the event belongs
      */
    def label: Label

  }

  trait UnScopedEvent extends Event

  /**
    * The global event tag.
    */
  val eventTag: String = "event"
}
