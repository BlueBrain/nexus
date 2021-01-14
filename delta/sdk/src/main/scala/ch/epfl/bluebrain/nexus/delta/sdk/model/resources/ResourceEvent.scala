package ch.epfl.bluebrain.nexus.delta.sdk.model.resources

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceRef, TagLabel}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of resource event states
  */
sealed trait ResourceEvent extends Event {

  /**
    * @return the resource identifier
    */
  def id: Iri

  /**
    * @return the project where the resource belongs to
    */
  def project: ProjectRef

  /**
    * @return the collection of known resource types
    */
  def types: Set[Iri]

}

object ResourceEvent {

  /**
    * Event representing a resource creation.
    *
    * @param id            the resource identifier
    * @param project       the project where the resource belongs
    * @param schema        the schema used to constrain the resource
    * @param schemaProject the project where the schema belongs
    * @param types         the collection of known resource types
    * @param source        the representation of the resource as posted by the subject
    * @param compacted     the compacted JSON-LD representation of the resource
    * @param expanded      the expanded JSON-LD representation of the resource
    * @param rev           the resource revision
    * @param instant       the instant when this event was created
    * @param subject       the subject which created this event
    */
  final case class ResourceCreated(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a resource modification.
    *
    * @param id            the resource identifier
    * @param project       the project where the resource belongs
    * @param schema        the schema used to constrain the resource
    * @param schemaProject the project where the schema belongs
    * @param types         the collection of known resource types
    * @param source        the representation of the resource as posted by the subject
    * @param compacted     the compacted JSON-LD representation of the resource
    * @param expanded      the expanded JSON-LD representation of the resource
    * @param rev           the resource revision
    * @param instant       the instant when this event was created
    * @param subject       the subject which created this event
    */
  final case class ResourceUpdated(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef.Revision,
      schemaProject: ProjectRef,
      types: Set[Iri],
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a tag addition to a resource.
    *
    * @param id        the resource identifier
    * @param project   the project where the resource belongs
    * @param types     the collection of known resource types
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''targetRev''
    * @param rev       the resource revision
    * @param instant   the instant when this event was created
    * @param subject   the subject which created this event
    */
  final case class ResourceTagAdded(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  /**
    * Event representing a resource deprecation.
    *
    * @param id          the resource identifier
    * @param project     the project where the resource belongs
    * @param types       the collection of known resource types
    * @param rev         the resource revision
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class ResourceDeprecated(
      id: Iri,
      project: ProjectRef,
      types: Set[Iri],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends ResourceEvent

  private val context = ContextValue(contexts.metadata)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"      => nxv.resourceId.prefix
      case "types"   => nxv.types.prefix
      case "source"  => nxv.source.prefix
      case "rev"     => nxv.rev.prefix
      case "instant" => nxv.instant.prefix
      case "subject" => nxv.eventSubject.prefix
      case other     => other
    })

  @nowarn("cat=unused")
  implicit private val compactedJsonLdEncoder: Encoder[CompactedJsonLd] = Encoder.instance(_.json)

  @nowarn("cat=unused")
  implicit private val expandedJsonLdEncoder: Encoder[ExpandedJsonLd] = Encoder.instance(_.json)

  @nowarn("cat=unused")
  implicit def resourceEventJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[ResourceEvent] = {
    implicit val subjectEncoder: Encoder[Subject]         = Identity.subjectIdEncoder
    implicit val encoder: Encoder.AsObject[ResourceEvent] =
      Encoder.AsObject.instance(
        deriveConfiguredEncoder[ResourceEvent].mapJsonObject(_.remove("compacted").remove("expanded")).encodeObject
      )

    JsonLdEncoder.compactedFromCirce[ResourceEvent](context)
  }
}
