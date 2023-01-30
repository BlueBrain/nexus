package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import io.circe.Json

/**
  * Enumeration of resource commands
  */
sealed trait ResourceCommand extends Product with Serializable {

  /**
    * @return
    *   the project where the resource belongs to
    */
  def project: ProjectRef

  /**
    * @return
    *   the resource identifier
    */
  def id: Iri

  /**
    * @return
    *   the last known revision of the resource
    */
  def rev: Int

  /**
    * @return
    *   the identity associated to this command
    */
  def subject: Subject

}

object ResourceCommand {

  /**
    * Command that signals the intent to create a new resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schema
    *   the schema used to constrain the resource
    * @param source
    *   the representation of the resource as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the resource
    * @param expanded
    *   the expanded JSON-LD representation of the resource
    * @param caller
    *   the subject which created this event
    */
  final case class CreateResource(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef,
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      caller: Caller
  ) extends ResourceCommand {

    override def rev: Int = 0

    def subject: Subject = caller.subject
  }

  /**
    * Command that signals the intent to update an existing resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schemaOpt
    *   the optional schema of the resource. A None value ignores the schema from this command
    * @param source
    *   the representation of the resource as posted by the subject
    * @param compacted
    *   the compacted JSON-LD representation of the resource
    * @param expanded
    *   the expanded JSON-LD representation of the resource
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   the subject which created this event
    */
  final case class UpdateResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      source: Json,
      compacted: CompactedJsonLd,
      expanded: ExpandedJsonLd,
      rev: Int,
      caller: Caller
  ) extends ResourceCommand {
    def subject: Subject = caller.subject
  }

  /**
    * Command that signals the intent to add a tag to an existing resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schemaOpt
    *   the optional schema of the resource. A None value ignores the schema from this operation
    * @param targetRev
    *   the revision that is being aliased with the provided ''tag''
    * @param tag
    *   the tag of the alias for the provided ''tagRev''
    * @param rev
    *   the last known revision of the resource
    * @param subject
    *   the subject which created this event
    */
  final case class TagResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      targetRev: Int,
      tag: UserTag,
      rev: Int,
      subject: Subject
  ) extends ResourceCommand

  /**
    * Command that signals the intent to delete a tag from an existing resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schemaOpt
    *   the optional schema of the resource. A None value ignores the schema from this operation
    * @param tag
    *   the tag to delete
    * @param rev
    *   the last known revision of the resource
    * @param subject
    *   the subject which created this event
    */
  final case class DeleteResourceTag(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      tag: UserTag,
      rev: Int,
      subject: Subject
  ) extends ResourceCommand

  /**
    * Command that signals the intent to deprecate a resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schemaOpt
    *   the optional schema of the resource. A None value ignores the schema from this operation
    * @param rev
    *   the last known revision of the resource
    * @param subject
    *   the subject which created this event
    */
  final case class DeprecateResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Int,
      subject: Subject
  ) extends ResourceCommand
}
