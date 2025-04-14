package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdAssembly
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult.*
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, ReservedResourceId}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{ResourceGenerationResult, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import munit.Location

import java.util.UUID

class ResourcesTrialSuite extends NexusSuite with ValidateResourceFixture {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val caller: Caller = Caller.Anonymous

  implicit private val res: RemoteContextResolution =
    RemoteContextResolution.fixedIO(
      contexts.metadata        -> ContextValue.fromFile("contexts/metadata.json"),
      contexts.shacl           -> ContextValue.fromFile("contexts/shacl.json"),
      contexts.schemasMetadata -> ContextValue.fromFile("contexts/schemas-metadata.json")
    )

  private val fetchResourceFail = IO.raiseError(new IllegalStateException("Should not be attempt to fetch a resource"))

  private val resolverContextResolution: ResolverContextResolution = ResolverContextResolution(res)

  private val am             = ApiMappings(Map("nxv" -> nxv.base, "Person" -> schema.Person))
  private val allApiMappings = am + Resources.mappings
  private val projBase       = nxv.base
  private val project        = ProjectGen.project("myorg", "myproject", base = projBase, mappings = am)
  private val projectRef     = project.ref
  private val fetchContext   =
    FetchContextDummy(Map(projectRef -> project.context.copy(apiMappings = allApiMappings)), Set.empty)

  private val id             = nxv + "id"
  private val source         = NexusSource(jsonContentOf("resources/resource.json", "id" -> id))
  private val resourceSchema = nxv + "schema"

  private def assertSuccess(
      result: ResourceGenerationResult
  )(schema: Option[SchemaResource])(implicit loc: Location) = {
    assertEquals(result.schema, schema)
    assertEquals(result.attempt.map(_.value.id), Right(id))
  }

  private def assertError(
      io: IO[ResourceGenerationResult]
  )(schema: Option[SchemaResource], error: ResourceRejection)(implicit loc: Location) =
    io.map { generated =>
      assertEquals(generated.schema, schema)
      assertEquals(generated.attempt.map(_.value), Left(error))
    }

  test("Successfully generates a resource") {
    val trial = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysValidate,
      fetchContext,
      resolverContextResolution,
      clock
    )
    trial.generate(projectRef, resourceSchema, source).map(assertSuccess(_)(None))
  }

  test("Successfully generates a resource with a new schema") {
    val trial = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysValidate,
      fetchContext,
      resolverContextResolution,
      clock
    )

    val anotherSchema = nxv + "anotherSchema"
    for {
      schemaSource <-
        loader.jsonContentOf("resources/schema.json").map(_.addContext(contexts.shacl, contexts.schemasMetadata))
      schema       <- SchemaGen
                        .schemaAsync(anotherSchema, project.ref, schemaSource.removeKeys(keywords.id))
                        .map(SchemaGen.resourceFor(_))
      result       <- trial.generate(projectRef, schema, source)
    } yield {
      assertSuccess(result)(Some(schema))
    }
  }

  test("Fail when validation raises an error") {
    val expectedError = ReservedResourceId(id)
    val trial         = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysFail(expectedError),
      fetchContext,
      resolverContextResolution,
      clock
    )

    assertError(trial.generate(projectRef, resourceSchema, source))(None, expectedError)
  }

  test("Validate a resource against a new schema reference") {
    val anotherSchema = nxv + "anotherSchema"
    val expected      = Validated(projectRef, Revision(anotherSchema, defaultSchemaRevision), defaultReport)
    val resource      = ResourceGen.currentState(projectRef, JsonLdAssembly.empty(id), Revision(resourceSchema, 1))

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysValidate,
      fetchContext,
      resolverContextResolution,
      clock
    )

    trial.validate(id, projectRef, Some(anotherSchema)).assertEquals(expected)
  }

  test("Validate a resource against its own schema") {
    val resource = ResourceGen.currentState(projectRef, JsonLdAssembly.empty(id), Revision(resourceSchema, 1))

    val expected = Validated(projectRef, Revision(resourceSchema, defaultSchemaRevision), defaultReport)

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysValidate,
      fetchContext,
      resolverContextResolution,
      clock
    )
    trial.validate(id, projectRef, None).assertEquals(expected)
  }

  test("Fail to validate a resource against the specified schema") {
    val resource      = ResourceGen.currentState(projectRef, JsonLdAssembly.empty(id), Revision(resourceSchema, 1))
    val anotherSchema = nxv + "anotherSchema"
    val expectedError =
      InvalidResource(id, Revision(anotherSchema, defaultSchemaRevision), defaultReport, resource.expanded)

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysFail(expectedError),
      fetchContext,
      resolverContextResolution,
      clock
    )

    trial.validate(id, projectRef, Some(anotherSchema)).interceptEquals(expectedError)
  }

}
