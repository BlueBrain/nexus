package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json
import munit.Location

class ValidateResourceSuite extends NexusSuite {

  implicit val api: JsonLdApi                       = JsonLdJavaApi.lenient
  implicit private def res: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.metadata        -> ContextValue.fromFile("contexts/metadata.json"),
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json")
    )

  private val project = ProjectRef.unsafe("org", "proj")

  private val subject: Subject = Identity.User("user", Label.unsafe("realm"))
  private val caller: Caller   = Caller(subject, Set(subject))

  private def schemaValue(id: Iri) = loader
    .jsonContentOf("resources/schema.json")
    .map(_.addContext(contexts.shacl, contexts.schemasMetadata))
    .flatMap { source =>
      SchemaGen.schemaAsync(id, project, source)
    }

  private val schemaId  = nxv + "my-schema"
  private val schemaRef = ResourceRef.Revision(schemaId, 1)
  private val schema    = schemaValue(schemaId)
    .map(SchemaGen.resourceFor(_))

  private val deprecatedSchemaId  = nxv + "deprecated"
  private val deprecatedSchemaRef = ResourceRef.Revision(deprecatedSchemaId, 1)
  private val deprecatedSchema    = schemaValue(deprecatedSchemaId)
    .map(SchemaGen.resourceFor(_).copy(deprecated = true))

  private val unconstrained = ResourceRef.Revision(schemas.resources, 1)

  private val fetchSchema: (ResourceRef, ProjectRef) => FetchResource[Schema] = {
    case (ref, p) if ref.iri == schemaId && p == project           => schema.map(Some(_))
    case (ref, p) if ref.iri == deprecatedSchemaId && p == project => deprecatedSchema.map(Some(_))
    case _                                                         => IO.none
  }
  private val schemaResolution: ResourceResolution[Schema]                    =
    ResourceResolutionGen.singleInProject(project, fetchSchema)

  private def sourceWithId(id: Iri)               =
    loader.jsonContentOf("resources/resource.json", "id" -> id)

  private def jsonLdWithId(id: Iri, source: Json) =
    for {
      expanded <- ExpandedJsonLd(source)
      graph    <- IO.fromEither(expanded.toGraph)
    } yield JsonLdAssembly(id, source, CompactedJsonLd.empty, expanded, graph, Set.empty)

  private def jsonLdWithId(id: Iri) = sourceWithId(id).flatMap { source => jsonLdWithId(id, source) }

  private val validateResource = ValidateResource(schemaResolution)

  private def assertResult(result: ValidationResult, expectedProject: ProjectRef, expectedSchema: ResourceRef.Revision)(
      implicit loc: Location
  ) = {
    assertEquals(result.project, expectedProject)
    assertEquals(result.schema, expectedSchema)
  }

  test("Validate a resource with the appropriate schema") {
    val id = nxv + "valid"
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, schemaRef, caller)
      result     <- validateResource(jsonLd, schemaClaim, enforceSchema = false)
    } yield {
      assertResult(result, project, schemaRef)
    }
  }

  test("Validate a resource with no schema and no schema enforcement is enabled") {
    val id = nxv + "valid"
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, unconstrained, caller)
      result     <- validateResource(jsonLd, schemaClaim, enforceSchema = false)
    } yield {
      assertResult(result, project, unconstrained)
    }
  }

  test("Reject a resource when the id starts with a reserved prefix") {
    val id = contexts.base / "fail"
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, schemaRef, caller)
      _          <- validateResource(jsonLd, schemaClaim, enforceSchema = false)
                      .interceptEquals(ReservedResourceId(id))
    } yield ()
  }

  test("Reject a resource with no schema and schema enforcement is enabled") {
    val id = nxv + "valid"
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, unconstrained, caller)
      _          <- validateResource(jsonLd, schemaClaim, enforceSchema = true).interceptEquals(SchemaIsMandatory(project))
    } yield ()
  }

  test("Reject a resource when schema is not found") {
    val id            = nxv + "valid"
    val unknownSchema = Latest(nxv + "not-found")
    val expectedError = InvalidSchemaRejection(
      unknownSchema,
      project,
      ResourceResolutionReport(
        ResolverReport.failed(
          nxv + "in-project",
          project -> ResolverResolutionRejection.ResourceNotFound(unknownSchema.iri, project)
        )
      )
    )
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, unknownSchema, caller)
      _          <- validateResource(jsonLd, schemaClaim, enforceSchema = true).interceptEquals(expectedError)
    } yield ()
  }

  test("Reject a resource when the resolved schema is deprecated") {
    val id = nxv + "valid"
    for {
      jsonLd     <- jsonLdWithId(id)
      schemaClaim = SchemaClaim(project, deprecatedSchemaRef, caller)
      _          <- validateResource(jsonLd, schemaClaim, enforceSchema = true).interceptEquals(
                      SchemaIsDeprecated(deprecatedSchemaId)
                    )
    } yield ()
  }

  test("Reject a resource when it can't be validated by the default schema") {
    val id = nxv + "valid"
    for {
      source     <- sourceWithId(id).map(_.removeKeys("name"))
      jsonLd     <- jsonLdWithId(id, source)
      schemaClaim = SchemaClaim(project, schemaRef, caller)
      _          <- validateResource(jsonLd, schemaClaim, enforceSchema = true).intercept[InvalidResource]
    } yield ()
  }

}
