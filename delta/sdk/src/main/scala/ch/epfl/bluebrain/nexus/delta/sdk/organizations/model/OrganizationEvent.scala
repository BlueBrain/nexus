package ch.epfl.bluebrain.nexus.delta.sdk.organizations.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.Organizations
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationEvent.OrganizationCreated
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.Codec

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends GlobalEvent {

  /**
    * The relative [[Iri]] of the organization
    */
  def id: Iri = Organizations.encodeId(label)

  /**
    * @return
    *   the organization Label
    */
  def label: Label

  /**
    * @return
    *   the organization UUID
    */
  def uuid: UUID

  /**
    * @return
    *   true if the event is [[OrganizationCreated]], false otherwise
    */
  def isCreated: Boolean = this match {
    case _: OrganizationCreated => true
    case _                      => false
  }
}

object OrganizationEvent {

  /**
    * Event representing organization creation.
    *
    * @param label
    *   the organization label
    * @param uuid
    *   the organization UUID
    * @param rev
    *   the organization revision
    * @param description
    *   an optional description of the organization
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class OrganizationCreated(
      label: Label,
      uuid: UUID,
      rev: Int,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    * Event representing organization update.
    *
    * @param label
    *   the organization label
    * @param uuid
    *   the organization UUID
    * @param rev
    *   the update revision
    * @param description
    *   an optional description of the organization
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class OrganizationUpdated(
      label: Label,
      uuid: UUID,
      rev: Int,
      description: Option[String],
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    * Event representing organization deprecation.
    *
    * @param label
    *   the organization label
    * @param uuid
    *   the organization UUID
    * @param rev
    *   the deprecation revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class OrganizationDeprecated(
      label: Label,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  /**
    * Event representing organization undeprecation.
    *
    * @param label
    *   the organization label
    * @param uuid
    *   the organization UUID
    * @param rev
    *   the deprecation revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class OrganizationUndeprecated(
      label: Label,
      uuid: UUID,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends OrganizationEvent

  @nowarn("cat=unused")
  val serializer: Serializer[Label, OrganizationEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration             = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[OrganizationEvent] = deriveConfiguredCodec[OrganizationEvent]
    Serializer(Organizations.encodeId)
  }
}
