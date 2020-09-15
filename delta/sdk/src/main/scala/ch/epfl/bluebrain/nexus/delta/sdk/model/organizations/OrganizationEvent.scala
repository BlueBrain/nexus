package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends Product with Serializable {

  /**
    * @return the permanent identifier for the organization
    */
  def id: UUID

  /**
    * @return the revision that this event generated
    */
  def rev: Long

  /**
    * @return the instant when this event was created
    */
  def instant: Instant

  /**
    * @return the subject which created this event
    */
  def subject: Subject
}

object OrganizationEvent {

  /**
    * Event representing organization creation.
    *
   * @param id           the permanent identifier of the organization
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationCreated(
      id: UUID,
      label: Label,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent {

    /**
      *  the revision number that this event generates
      */
    val rev: Long = 1L
  }

  /**
    * Event representing organization update.
    *
   * @param id           the permanent identifier of the organization
    * @param rev          the update revision
    * @param label        the organization label
    * @param description  an optional description of the organization
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationUpdated(
      id: UUID,
      rev: Long,
      label: String,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    *  Event representing organization deprecation.
    *
   * @param id           the permanent identifier of the organization
    * @param rev          the deprecation revision
    * @param instant      the instant when this event was created
    * @param subject      the subject which created this event
    */
  final case class OrganizationDeprecated(
      id: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent
}
