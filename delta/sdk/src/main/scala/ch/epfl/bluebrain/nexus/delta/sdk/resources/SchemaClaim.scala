package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * Defines a claim for a schema to apply on a resource. This claim is relevant depending on:
  *   - If the operation on the resource is a creation or an update
  *   - If the provided schema reference points to the unconstrained one
  *   - If enforcing schemas is set at the project level
  */
sealed trait SchemaClaim {

  def project: ProjectRef

}

object SchemaClaim {

  type SubmitOnDefinedSchema = (ProjectRef, ResourceRef, Caller) => IO[ValidationResult]

  sealed trait DefinedSchemaClaim extends SchemaClaim {
    def schemaRef: ResourceRef
  }

  final case class CreateWithSchema(project: ProjectRef, schemaRef: ResourceRef, caller: Caller)
      extends DefinedSchemaClaim
  final case class CreateUnconstrained(project: ProjectRef) extends SchemaClaim

  final case class UpdateToSchema(project: ProjectRef, schemaRef: ResourceRef, caller: Caller)
      extends DefinedSchemaClaim

  final case class UpdateToUnconstrained(project: ProjectRef) extends SchemaClaim

  final case class KeepUnconstrained(project: ProjectRef) extends SchemaClaim

  private def isUnconstrained(schema: ResourceRef): Boolean = schema.iri == schemas.resources

  /**
    * Generate the schema claim on resource creation
    */
  def onCreate(project: ProjectRef, schema: ResourceRef, caller: Caller): SchemaClaim =
    if (isUnconstrained(schema)) {
      CreateUnconstrained(project)
    } else
      CreateWithSchema(project, schema, caller)

  /**
    * Generate the schema claim on resource update
    */
  def onUpdate(project: ProjectRef, newSchema: ResourceRef, currentSchema: ResourceRef, caller: Caller): SchemaClaim =
    onUpdate(project, Some(newSchema), currentSchema, caller)

  /**
    * Generate the schema claim on resource update
    */
  def onUpdate(
      project: ProjectRef,
      newSchemaOpt: Option[ResourceRef],
      currentSchema: ResourceRef,
      caller: Caller
  ): SchemaClaim =
    newSchemaOpt match {
      case Some(newSchema) if !isUnconstrained(newSchema) => UpdateToSchema(project, newSchema, caller)
      case Some(_) if !isUnconstrained(currentSchema)     => UpdateToUnconstrained(project)
      case Some(_)                                        => KeepUnconstrained(project)
      case None if isUnconstrained(currentSchema)         => KeepUnconstrained(project)
      case None                                           => UpdateToSchema(project, currentSchema, caller)
    }

}
