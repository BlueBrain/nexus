package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

import java.time.Instant

/**
  * Super type of all events.
  */
sealed trait Event extends Product with Serializable {

  /**
    * @return
    *   the revision this events generates
    */
  def rev: Long

  /**
    * @return
    *   the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return
    *   the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject
}

object Event {

  trait UnScopedEvent extends Event

  trait OrganizationScopedEvent extends Event {

    /**
      * @return
      *   the organization where the event belongs
      */
    def organizationLabel: Label
  }

  trait ProjectScopedEvent extends OrganizationScopedEvent {

    /**
      * @return
      *   the project where the event belongs
      */
    def project: ProjectRef

    /**
      * @return
      *   the parent organization label
      */
    def organizationLabel: Label = project.organization

  }

  /**
    * The global event tag.
    */
  val eventTag: String = "event"
}
