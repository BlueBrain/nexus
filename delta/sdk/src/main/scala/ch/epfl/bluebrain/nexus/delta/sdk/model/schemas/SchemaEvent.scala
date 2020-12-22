package ch.epfl.bluebrain.nexus.delta.sdk.model.schemas

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, TagLabel}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of schema event states
  */
sealed trait SchemaEvent extends Event {

  /**
    * @return the schema identifier
    */
  def id: Iri

  /**
    * @return the project where the schema belongs to
    */
  def project: ProjectRef

}

object SchemaEvent {

  /**
    * Event representing a schema creation.
    *
    * @param id          the schema identifier
    * @param project     the project where the schema belongs
    * @param source      the representation of the schema as posted by the subject
    * @param compacted   the compacted JSON-LD representation of the schema
    * @param expanded    the expanded JSON-LD representation of the schema with the imports resolutions applied
    * @param rev         the schema revision
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class SchemaCreated(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a schema modification.
    *
    * @param id          the schema identifier
    * @param project     the project where the schema belongs
    * @param source      the representation of the schema as posted by the subject
    * @param compacted   the compacted JSON-LD representation of the schema
    * @param expanded    the expanded JSON-LD representation of the schema with the imports resolutions applied
    * @param rev         the schema revision
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class SchemaUpdated(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a tag addition to a schema.
    *
    * @param id        the schema identifier
    * @param project   the project where the schema belongs
    * @param targetRev the revision that is being aliased with the provided ''tag''
    * @param tag       the tag of the alias for the provided ''targetRev''
    * @param rev       the schema revision
    * @param instant   the instant when this event was created
    * @param subject   the subject which created this event
    */
  final case class SchemaTagAdded(
      id: Iri,
      project: ProjectRef,
      targetRev: Long,
      tag: TagLabel,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  /**
    * Event representing a schema deprecation.
    *
    * @param id          the schema identifier
    * @param project     the project where the schema belongs
    * @param rev         the schema revision
    * @param instant     the instant when this event was created
    * @param subject     the subject which created this event
    */
  final case class SchemaDeprecated(
      id: Iri,
      project: ProjectRef,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends SchemaEvent

  private val context = ContextValue(contexts.metadata)

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default
    .withDiscriminator(keywords.tpe)
    .copy(transformMemberNames = {
      case "id"      => nxv.schemaId.prefix
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
  implicit def schemaEventJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[SchemaEvent] = {
    implicit val subjectEncoder: Encoder[Subject]       = Identity.subjectIdEncoder
    implicit val encoder: Encoder.AsObject[SchemaEvent] =
      Encoder.AsObject.instance(
        deriveConfiguredEncoder[SchemaEvent].mapJsonObject(_.remove("compacted").remove("expanded")).encodeObject
      )

    JsonLdEncoder.compactedFromCirce[SchemaEvent](context)
  }
}
