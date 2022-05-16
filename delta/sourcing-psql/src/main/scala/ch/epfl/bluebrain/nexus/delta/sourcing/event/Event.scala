package ch.epfl.bluebrain.nexus.delta.sourcing.event

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}

import java.time.Instant

/**
  * Super type of all events.
  */
sealed trait Event extends Product with Serializable {

  /**
    * @return
    *   the revision this events generates
    */
  def rev: Int

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

  trait GlobalEvent extends Event

  trait ScopedEvent extends Event {

    /**
      * @return
      *   the project where the event belongs
      */
    def project: ProjectRef

    /**
      * @return
      *   the parent organization label
      */
    def organization: Label = project.organization

  }
}
