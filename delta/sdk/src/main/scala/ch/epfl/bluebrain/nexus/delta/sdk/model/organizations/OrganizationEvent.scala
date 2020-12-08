package ch.epfl.bluebrain.nexus.delta.sdk.model.organizations

import java.time.Instant
import java.util.UUID
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, Label, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._

import scala.annotation.nowarn

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
  implicit def organizationEventJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[OrganizationEvent] = {
    implicit val subjectEncoder: Encoder[Subject]             = Identity.subjectIdEncoder
    implicit val encoder: Encoder.AsObject[OrganizationEvent] = Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[OrganizationEvent]
        .mapJsonObject(_.add("_organizationId", ResourceUris.organization(ev.label).accessUri.asJson))
        .encodeObject(ev)
    }

    JsonLdEncoder
      .computeFromCirce[OrganizationEvent](context)
  }
}
