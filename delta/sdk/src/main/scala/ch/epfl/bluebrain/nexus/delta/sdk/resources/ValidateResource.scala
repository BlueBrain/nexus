package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.implicits.catsSyntaxMonadError
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.JsonLdApi
import ch.epfl.bluebrain.nexus.delta.rdf.shacl.{ShaclEngine, ValidationReport}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.ResourceResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidJsonLdFormat, InvalidResource, InvalidSchemaRejection, ReservedResourceId, ResourceShaclEngineRejection, SchemaIsDeprecated}
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import monix.bio.IO

/**
  * Allows to validate the resource:
  *   - Validate it against the provided schema
  *   - Checking if the provided resource id is not reserved
  */
trait ValidateResource {

  /**
    * Validate against a schema reference
    * @param resourceId
    *   the id of the resource
    * @param expanded
    *   its expanded JSON-LD representation
    * @param schemaRef
    *   the reference of the schema
    * @param projectRef
    *   the project of the resource where to resolve the reference of the schema
    * @param caller
    *   the caller
    */
  def apply(
      resourceId: Iri,
      expanded: ExpandedJsonLd,
      schemaRef: ResourceRef,
      projectRef: ProjectRef,
      caller: Caller
  ): IO[ResourceRejection, ValidationResult]

  /**
    * Validate against a schema
    * @param resourceId
    *   the id of the resource
    * @param expanded
    *   its expanded JSON-LD representation
    * @param schema
    *   the schema to validate against
    */
  def apply(
      resourceId: Iri,
      expanded: ExpandedJsonLd,
      schema: ResourceF[Schema]
  ): IO[ResourceRejection, ValidationResult]
}

object ValidateResource {

  def apply(resourceResolution: ResourceResolution[Schema])(implicit jsonLdApi: JsonLdApi): ValidateResource =
    new ValidateResource {
      override def apply(
          resourceId: Iri,
          expanded: ExpandedJsonLd,
          schemaRef: ResourceRef,
          projectRef: ProjectRef,
          caller: Caller
      ): IO[ResourceRejection, ValidationResult] =
        if (isUnconstrained(schemaRef))
          assertNotReservedId(resourceId) >>
            toGraph(resourceId, expanded) >>
            IO.pure(NoValidation(projectRef))
        else
          for {
            schema <- resolveSchema(resourceResolution, projectRef, schemaRef, caller)
            result <- apply(resourceId, expanded, schema)
          } yield result

      def apply(
          resourceId: Iri,
          expanded: ExpandedJsonLd,
          schema: ResourceF[Schema]
      ): IO[ResourceRejection, ValidationResult] =
        for {
          _        <- assertNotReservedId(resourceId)
          graph    <- toGraph(resourceId, expanded)
          schemaRef = ResourceRef.Revision(schema.id, schema.rev)
          report   <- shaclValidate(resourceId, graph, schemaRef, schema)
          _        <- IO.raiseWhen(!report.isValid())(InvalidResource(resourceId, schemaRef, report, expanded))
        } yield Validated(schema.value.project, ResourceRef.Revision(schema.id, schema.rev), report)

      private def toGraph(id: Iri, expanded: ExpandedJsonLd): IO[ResourceRejection, Graph] =
        IO.fromEither(expanded.toGraph).mapError(err => InvalidJsonLdFormat(Some(id), err))

      private def shaclValidate(
          resourceId: Iri,
          graph: Graph,
          schemaRef: ResourceRef,
          schema: ResourceF[Schema]
      ): IO[ResourceRejection, ValidationReport] = {
        ShaclEngine(graph ++ schema.value.ontologies, schema.value.shapes, reportDetails = true, validateShapes = false)
          .adaptError(e => ResourceShaclEngineRejection(resourceId, schemaRef, e.toString))
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
      }.toBIO[ResourceRejection]
    }
}
