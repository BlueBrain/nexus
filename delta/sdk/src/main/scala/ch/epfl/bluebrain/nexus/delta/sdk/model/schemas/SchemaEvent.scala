package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.instances._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Event.ProjectScopedEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, NonEmptyList, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax._
import io.circe.{Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of schema event states
  */
sealed trait SchemaEvent extends ProjectScopedEvent {

  /**
    * @return
    *   the schema identifier
    */
  def id: Iri

  /**
    * @return
    *   the project where the schema belongs to
    */
  def project: ProjectRef

}

object SchemaEvent {

  /**
    * Event representing a schema creation.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param source
    *   the representation of the schema as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the schema
    * @param expanded
    *   the list of expanded JSON-LD representation of the schema with the imports resolutions applied
    * @param rev
    *   the schema revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class SchemaCreated(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: NonEmptyList[ExpandedJsonLd],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a schema modification.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param source
    *   the representation of the schema as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the schema
    * @param expanded
    *   the list of expanded JSON-LD representation of the schema with the imports resolutions applied
    * @param rev
    *   the schema revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class SchemaUpdated(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: NonEmptyList[ExpandedJsonLd],
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a tag addition to a schema.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''targetRev''
    * @param rev
    *   the schema revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class SchemaTagAdded(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: UserTag,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a tag deletion from a schema.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param tag
    *   the tag that was deleted
    * @param rev
    *   the schema revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class SchemaTagDeleted(
      id: Iri,
      project: ProjectRef,
      tag: UserTag,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a schema deprecation.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param rev
    *   the schema revision
    * @param instant
    *   the instant when this event was created
    * @param subject
    *   the subject which created this event
    */
  final case class SchemaDeprecated(
      id: Iri,
      project: ProjectRef,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  private val context = ContextValue(contexts.metadata, contexts.shacl)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"      => nxv.schemaId.prefix
      case "source"  => nxv.source.prefix
      case "project" => nxv.project.prefix
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
  implicit def schemaEventEncoder(implicit base: BaseUri): Encoder.AsObject[SchemaEvent] = {
    implicit val subjectEncoder: Encoder[Subject]       = Identity.subjectIdEncoder
    implicit val projectRefEncoder: Encoder[ProjectRef] = Encoder.instance(ResourceUris.projectUri(_).asJson)
    Encoder.encodeJsonObject.contramapObject { event =>
      deriveConfiguredEncoder[SchemaEvent]
        .encodeObject(event)
        .remove("compacted")
        .remove("expanded")
        .add(nxv.constrainedBy.prefix, schemas.shacl.asJson)
        .add(nxv.types.prefix, Set(nxv.Schema).asJson)
        .add(nxv.resourceId.prefix, event.id.asJson)
        .add(keywords.context, context.value)
    }
  }
}
