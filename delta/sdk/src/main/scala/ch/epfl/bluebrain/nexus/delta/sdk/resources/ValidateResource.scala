package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated, SchemaIsMandatory}
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
      resourceResolution: ResourceResolution[Schema]
  )(implicit rcr: RemoteContextResolution): ValidateResource =
    new ValidateResource {
      override def apply(
          jsonld: JsonLdAssembly,
          schemaClaim: SchemaClaim,
          enforceSchema: Boolean
      ): IO[ValidationResult] =
        if (schemaClaim.isUnconstrained)
          assertMandatorySchema(schemaClaim.project, enforceSchema) >>
            assertNotReservedId(jsonld.id) >>
            IO.pure(NoValidation(schemaClaim.project))
        else
          for {
            schema <- resolveSchema(schemaClaim)
            result <- apply(jsonld, schema)
          } yield result

      def apply(jsonld: JsonLdAssembly, schema: ResourceF[Schema]): IO[ValidationResult] =
        for {
          _        <- assertNotReservedId(jsonld.id)
          schemaRef = ResourceRef.Revision(schema.id, schema.rev)
          report   <- shaclValidate(jsonld, schemaRef, schema)
          _        <- IO.raiseWhen(!report.isValid())(InvalidResource(jsonld.id, schemaRef, report, jsonld.expanded))
        } yield Validated(schema.value.project, ResourceRef.Revision(schema.id, schema.rev), report)

      private def shaclValidate(
          jsonld: JsonLdAssembly,
          schemaRef: ResourceRef,
          schema: ResourceF[Schema]
      ): IO[ValidationReport] =
        ShaclEngine(
          jsonld.graph ++ schema.value.ontologies,
          schema.value.shapes,
          reportDetails = true,
          validateShapes = false
        ).adaptError { e =>
          ResourceShaclEngineRejection(jsonld.id, schemaRef, e.getMessage)
        }

      private def assertMandatorySchema(project: ProjectRef, enforceSchema: Boolean) =
        IO.raiseWhen(enforceSchema)(SchemaIsMandatory(project))

      private def assertNotDeprecated(schema: ResourceF[Schema]) = {
        IO.raiseWhen(schema.deprecated)(SchemaIsDeprecated(schema.value.id))
      }

      private def assertNotReservedId(resourceId: Iri) = {
        IO.raiseWhen(resourceId.startsWith(contexts.base))(ReservedResourceId(resourceId))
      }

      private def resolveSchema(schemaClaim: SchemaClaim) = {
        resourceResolution
          .resolve(schemaClaim.schemaRef, schemaClaim.project)(schemaClaim.caller)
          .flatMap { result =>
            val invalidSchema = result.leftMap(InvalidSchemaRejection(schemaClaim.schemaRef, schemaClaim.project, _))
            IO.fromEither(invalidSchema)
          }
          .flatTap(schema => assertNotDeprecated(schema))
      }
    }
}
