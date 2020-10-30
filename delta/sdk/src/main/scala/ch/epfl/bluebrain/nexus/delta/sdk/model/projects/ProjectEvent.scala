package ch.epfl.bluebrain.nexus.delta.sdk.model.projects

import java.time.Instant
import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.Lens
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, Label}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

import scala.annotation.nowarn
import scala.collection.mutable.ListBuffer

/**
  * Enumeration of Project event types.
  */
sealed trait ProjectEvent extends Event {

  /**
    * @return the project ref
    */
  def ref: ProjectRef = ProjectRef(organizationLabel, label)

  /**
    * @return the project label
    */
  def label: Label

  /**
    * @return the project uuid
    */
  def uuid: UUID

  /**
    * @return the parent organization label
    */
  def organizationLabel: Label

  /**
    * @return the parent organization unique identifier
    */
  def organizationUuid: UUID
}

object ProjectEvent {

  /**
    * Evidence that a project has been created.
    *
    * @param label             the project label
    * @param uuid              the project uuid
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the project revision
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectCreated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIRI,
      vocab: PrefixIRI,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been updated.
    *
    * @param label             the project label
    * @param uuid              the project uuid
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param description       an optional project description
    * @param apiMappings       the project API mappings
    * @param base              the base Iri for generated resource IDs ending with ''/'' or ''#''
    * @param vocab             an optional vocabulary for resources with no context ending with ''/'' or ''#''
    * @param rev               the revision number that this event generates
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectUpdated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      description: Option[String],
      apiMappings: Map[String, Iri],
      base: PrefixIRI,
      vocab: PrefixIRI,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  /**
    * Evidence that a project has been deprecated.
    *
    * @param label             the label (segment) of the project
    * @param uuid              the permanent identifier for the project
    * @param organizationLabel the parent organization label
    * @param organizationUuid  the parent organization uuid
    * @param rev               the revision number that this event generates
    * @param instant           the timestamp associated to this event
    * @param subject           the identity associated to this event
    */
  final case class ProjectDeprecated(
      label: Label,
      uuid: UUID,
      organizationLabel: Label,
      organizationUuid: UUID,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ProjectEvent

  private val context = ContextValue(contexts.resource, contexts.projects)

  implicit private[projects] val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "label"             => nxv.label.prefix
      case "uuid"              => nxv.uuid.prefix
      case "organizationLabel" => nxv.organizationLabel.prefix
      case "organizationUuid"  => nxv.organizationUuid.prefix
      case "rev"               => nxv.rev.prefix
      case "instant"           => nxv.instant.prefix
      case "subject"           => nxv.eventSubject.prefix
      case other               => other
    })

  implicit val apiMappingEncoder: Encoder[Map[String, Iri]] =
    Encoder.encodeJson.contramap { map =>
      Json.arr(
        map
          .foldLeft(ListBuffer.newBuilder[Json]) { case (acc, (prefix, namespace)) =>
            acc += Json.obj("prefix" -> Json.fromString(prefix), "namespace" -> namespace.asJson)
          }
          .result()
          .toSeq: _*
      )
    }

  @nowarn("cat=unused")
  implicit def projectEventJsonLdEncoder(implicit
      baseUri: BaseUri,
      iriLens: Lens[ProjectRef, Iri]
  ): JsonLdEncoder[ProjectEvent] = {
    implicit val subjectEncoder: Encoder[Subject]        = Identity.subjectIdEncoder
    implicit val encoder: Encoder.AsObject[ProjectEvent] = Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[ProjectEvent]
        .mapJsonObject(_.add("@id", Json.fromString(iriLens.get(ev.ref).toString)))
        .encodeObject(ev)
    }

    JsonLdEncoder.compactFromCirce[ProjectEvent](context)
  }
}
