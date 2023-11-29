package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * Allows to validate the resource:
  *   - Validate it against the provided schema
  *   - Checking if the provided resource id is not reserved
  */
trait ValidateResource {

  /**
    * Validate against a schema reference
    * @param jsonld
    *   the generated resource
    * @param schemaRef
    *   the reference of the schema
    * @param projectRef
    *   the project of the resource where to resolve the reference of the schema
    * @param caller
    *   the caller
    */
  def apply(
      jsonld: JsonLdAssembly,
      schemaRef: ResourceRef,
      projectRef: ProjectRef,
      caller: Caller
  ): IO[ValidationResult]

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
          schemaRef: ResourceRef,
          projectRef: ProjectRef,
          caller: Caller
      ): IO[ValidationResult] =
        if (isUnconstrained(schemaRef))
          assertNotReservedId(jsonld.id) >>
            IO.pure(NoValidation(projectRef))
        else
          for {
            schema <- resolveSchema(resourceResolution, projectRef, schemaRef, caller)
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

      private def assertNotDeprecated(schema: ResourceF[Schema]) = {
        IO.raiseWhen(schema.deprecated)(SchemaIsDeprecated(schema.value.id))
      }

      private def assertNotReservedId(resourceId: Iri) = {
        IO.raiseWhen(resourceId.startsWith(contexts.base))(ReservedResourceId(resourceId))
      }

      private def isUnconstrained(schemaRef: ResourceRef) = {
        schemaRef == Latest(schemas.resources) || schemaRef == ResourceRef.Revision(schemas.resources, 1)
      }

      private def resolveSchema(
          resourceResolution: ResourceResolution[Schema],
          projectRef: ProjectRef,
          schemaRef: ResourceRef,
          caller: Caller
      ) = {
        resourceResolution
          .resolve(schemaRef, projectRef)(caller)
          .flatMap { result =>
            val invalidSchema = result.leftMap(InvalidSchemaRejection(schemaRef, projectRef, _))
            IO.fromEither(invalidSchema)
          }
          .flatTap(schema => assertNotDeprecated(schema))
      }
    }
}
