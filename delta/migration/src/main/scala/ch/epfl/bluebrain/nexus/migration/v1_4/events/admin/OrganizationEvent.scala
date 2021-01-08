package ch.epfl.bluebrain.nexus.migration.v1_4.events.admin

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.migration.v1_4.events.ToMigrateEvent

import java.time.Instant
import java.util.UUID

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends ToMigrateEvent {

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
      label: Label,
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
