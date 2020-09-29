package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, Label}

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends Event {

  /**
    * @return the organization Label
    */
  def label: Label

  /**
    * @return the organization UUID
    */
  def uuid: UUID
}

object OrganizationEvent {

  /**
    * Event representing organization creation.
    *
    * @param label       the organization label
    * @param uuid        the organization UUID
    * @param rev         the organization revision
    * @param description an optional description of the organization
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class OrganizationCreated(
      label: Label,
      uuid: UUID,
      rev: Long,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    * Event representing organization update.
    *
    * @param label       the organization label
    * @param uuid        the organization UUID
    * @param rev         the update revision
    * @param description an optional description of the organization
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class OrganizationUpdated(
      label: Label,
      uuid: UUID,
      rev: Long,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    *  Event representing organization deprecation.
    *
    * @param label   the organization label
    * @param uuid    the organization UUID
    * @param rev     the deprecation revision
    * @param instant the instant when this event was created
    * @param subject the subject which created this event
    */
  final case class OrganizationDeprecated(
      label: Label,
      uuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent
}
