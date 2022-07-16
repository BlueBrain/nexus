package ch.epfl.bluebrain.nexus.delta.sdk.schemas.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.Json

/**
  * Enumeration of schema commands
  */
sealed trait SchemaCommand extends Product with Serializable {

  /**
    * @return
    *   the project where the schema belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the schema identifier
    */
  def id: Iri

  /**
    * the last known revision of the schema
    * @return
    */
  def rev: Int

  /**
    * @return
    *   the identity associated to this command
    */
  def subject: Subject

}

object SchemaCommand {

  /**
    * Command that signals the intent to create a new schema.
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
    * @param subject
    *   the subject which created this event
    */
  final case class CreateSchema(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: NonEmptyList[ExpandedJsonLd],
      subject: Subject
  ) extends SchemaCommand {
    override def rev: Int = 0
  }

  /**
    * Command that signals the intent to update an existing schema.
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
    *   the last known revision of the schema
    * @param subject
    *   the subject which created this event
    */
  final case class UpdateSchema(
      id: Iri,
      project: ProjectRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: NonEmptyList[ExpandedJsonLd],
      rev: Int,
      subject: Subject
  ) extends SchemaCommand

  /**
    * Command that signals the intent to add a tag to an existing schema.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the schema
    * @param subject
    *   the subject which created this event
    */
  final case class TagSchema(
      id: Iri,
      project: ProjectRef,
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      subject: Subject
  ) extends SchemaCommand

  /**
    * Command that signals the intent to delete a tag from an existing schema.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param tag
    *   the tag of to delete
    * @param rev
    *   the last known revision of the schema
    * @param subject
    *   the subject which created this event
    */
  final case class DeleteSchemaTag(
      id: Iri,
      project: ProjectRef,
      tag: UserTag,
      rev: Int,
      subject: Subject
  ) extends SchemaCommand

  /**
    * Command that signals the intent to deprecate a schema.
    *
    * @param id
    *   the schema identifier
    * @param project
    *   the project where the schema belongs
    * @param rev
    *   the last known revision of the schema
    * @param subject
    *   the subject which created this event
    */
  final case class DeprecateSchema(
      id: Iri,
      project: ProjectRef,
      rev: Int,
      subject: Subject
  ) extends SchemaCommand
}
