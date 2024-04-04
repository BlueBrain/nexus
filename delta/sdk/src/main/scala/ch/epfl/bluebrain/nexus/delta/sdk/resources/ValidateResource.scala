package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ValidateShacl, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.SchemaClaim.SubmitOnDefinedSchema
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * Allows to validate the resource:
  *   - Validate it against the provided schema
  *   - Checking if the provided resource id is not reserved
  */
trait ValidateResource {

  /**
    * Validate against a schema reference
    *
    * @param jsonld
    *   the generated resource
    * @param schema
    *   the schema claim
    * @param enforceSchema
    *   whether to ban unconstrained resources
    */
  def apply(jsonld: JsonLdAssembly, schema: SchemaClaim, enforceSchema: Boolean): IO[ValidationResult]

  /**
    * Validate against a schema
    *
    * @param jsonld
    *   the generated resource
    * @param schema
    *   the schema to validate against
    */
  def apply(
      jsonld: JsonLdAssembly,
      schema: ResourceF[Schema]
  ): IO[ValidationResult]
}

object ValidateResource {

  def apply(
      resourceResolution: ResourceResolution[Schema],
      validateShacl: ValidateShacl
  ): ValidateResource =
    new ValidateResource {
      override def apply(
          jsonld: JsonLdAssembly,
          schemaClaim: SchemaClaim,
          enforceSchema: Boolean
      ): IO[ValidationResult] = {
        val submitOnDefinedSchema: SubmitOnDefinedSchema = resolveSchema(_, _, _).flatMap(apply(jsonld, _))
        assertNotReservedId(jsonld.id) >> schemaClaim.validate(enforceSchema)(submitOnDefinedSchema)
      }

      def apply(jsonld: JsonLdAssembly, schema: ResourceF[Schema]): IO[ValidationResult] = {
        val schemaRef = ResourceRef.Revision(schema.id, schema.rev)
        for {
          report <- shaclValidate(jsonld, schemaRef, schema)
          _      <- IO.raiseWhen(!report.isValid())(InvalidResource(jsonld.id, schemaRef, report, jsonld.expanded))
        } yield Validated(schema.value.project, ResourceRef.Revision(schema.id, schema.rev), report)
      }

      private def shaclValidate(
          jsonld: JsonLdAssembly,
          schemaRef: ResourceRef,
          schema: ResourceF[Schema]
      ): IO[ValidationReport] =
        validateShacl(
          jsonld.graph ++ schema.value.ontologies,
          schema.value.shapes,
          reportDetails = true
        ).adaptError { e =>
          ResourceShaclEngineRejection(jsonld.id, schemaRef, e.getMessage)
        }

      private def assertNotDeprecated(schema: ResourceF[Schema]) = {
        IO.raiseWhen(schema.deprecated)(SchemaIsDeprecated(schema.value.id))
      }

      private def assertNotReservedId(resourceId: Iri) = {
        IO.raiseWhen(resourceId.startsWith(contexts.base))(ReservedResourceId(resourceId))
      }

      private def resolveSchema(project: ProjectRef, schema: ResourceRef, caller: Caller) = {
        resourceResolution
          .resolve(schema, project)(caller)
          .flatMap { result =>
            val invalidSchema = result.leftMap(InvalidSchemaRejection(schema, project, _))
            IO.fromEither(invalidSchema)
          }
          .flatTap(schema => assertNotDeprecated(schema))
      }
    }
}
