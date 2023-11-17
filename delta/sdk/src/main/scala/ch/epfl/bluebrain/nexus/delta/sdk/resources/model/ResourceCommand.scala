package ch.epfl.bluebrain.nexus.delta.sdk.resources.model

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdSourceProcessor.JsonLdResult
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

  sealed trait ModifyCommand {
    def id: Iri
    def project: ProjectRef
    def schemaOpt: Option[ResourceRef]
    def rev: Int
  }

  /** A [[ModifyCommand]] to use when the schema is not optional */
  trait ModifyCommandWithSchema extends ModifyCommand {
    def schemaRef: ResourceRef
    def schemaOpt: Option[ResourceRef] = schemaRef.some
  }

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
    * @param jsonld
    *   the jsonld representation of the resource
    * @param caller
    *   the subject which created this event
    * @param tag
    *   an optional tag to link to the resource at creation
    */
  final case class CreateResource(
      id: Iri,
      project: ProjectRef,
      schema: ResourceRef,
      source: Json,
      jsonld: JsonLdResult,
      caller: Caller,
      tag: Option[UserTag]
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
    * @param jsonld
    *   the jsonld representation of the resource
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   the subject which created this event
    * @param tag
    *   an optional tag to link to this new revision
    */
  final case class UpdateResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      source: Json,
      jsonld: JsonLdResult,
      rev: Int,
      caller: Caller,
      tag: Option[UserTag]
  ) extends ResourceCommand
      with ModifyCommand {
    def subject: Subject = caller.subject
  }

  /**
    * Command that signals the intent to refresh an existing resource.
    *
    * @param id
    *   the resource identifier
    * @param project
    *   the project where the resource belongs
    * @param schemaOpt
    *   the optional schema of the resource. A None value ignores the schema from this command
    * @param jsonld
    *   the jsonld representation of the resource
    * @param rev
    *   the last known revision of the resource
    * @param caller
    *   the subject which created this event
    */
  final case class RefreshResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      jsonld: JsonLdResult,
      rev: Int,
      caller: Caller
  ) extends ResourceCommand
      with ModifyCommand {
    def subject: Subject = caller.subject
  }

  /**
    * Command that signals the intent to update the schema attached to a resource
    *
    * @param id
    *   resource identifier
    * @param project
    *   project where the resource belongs
    * @param schemaRef
    *   schema of the resource
    * @param rev
    *   last known revision of the resource
    * @param caller
    *   subject which created this event
    * @param tag
    *   an optional tag to link to this new revision
    */
  final case class UpdateResourceSchema(
      id: Iri,
      project: ProjectRef,
      schemaRef: ResourceRef,
      rev: Int,
      caller: Caller,
      tag: Option[UserTag]
  ) extends ResourceCommand
      with ModifyCommandWithSchema {
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
      with ModifyCommand

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
      with ModifyCommand

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
      with ModifyCommand

  /**
    * Command that signals the intent to undeprecate a resource.
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
  final case class UndeprecateResource(
      id: Iri,
      project: ProjectRef,
      schemaOpt: Option[ResourceRef],
      rev: Int,
      subject: Subject
  ) extends ResourceCommand
      with ModifyCommand
}
