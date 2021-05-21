package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.OrganizationScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationEvent.OrganizationCreated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label, ResourceUris}
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn

/**
  * Enumeration of organization event states
  */
sealed trait OrganizationEvent extends OrganizationScopedEvent {

  /**
    * @return the organization Label
    */
  def label: Label

  /**
    * @return the organization UUID
    */
  def uuid: UUID

  /**
    * @return true if the event is [[OrganizationCreated]], false otherwise
    */
  def isCreated: Boolean = this match {
    case _: OrganizationCreated => true
    case _                      => false
  }

  override def organizationLabel: Label = label
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

  private val context = ContextValue(contexts.metadata, contexts.organizations)

  implicit private[organizations] val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "label"   => nxv.label.prefix
      case "uuid"    => nxv.uuid.prefix
      case "rev"     => nxv.rev.prefix
      case "instant" => nxv.instant.prefix
      case "subject" => nxv.eventSubject.prefix
      case other     => other
    })

  @nowarn("cat=unused")
  implicit def organizationEventEncoder(implicit base: BaseUri): Encoder.AsObject[OrganizationEvent] = {
    implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder
    Encoder.encodeJsonObject.contramapObject { event =>
      deriveConfiguredEncoder[OrganizationEvent]
        .encodeObject(event)
        .add("_organizationId", ResourceUris.organization(event.label).accessUri.asJson)
        .add(nxv.resourceId.prefix, ResourceUris.organization(event.label).accessUri.asJson)
        .add(keywords.context, context.value)
    }
  }
}
