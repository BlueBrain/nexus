package ch.epfl.bluebrain.nexus.delta.sdk.resources

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv, schema}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ApiMappings
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, ProjectContextRejection, ReservedResourceId}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceGenerationResult, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsEffectSuite
import munit.Location

import java.util.UUID

class ResourcesTrialSuite extends CatsEffectSuite with ValidateResourceFixture {

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)

  implicit private val caller: Caller = Caller.Anonymous

  implicit private val api: JsonLdApi = JsonLdJavaApi.strict

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
  private val fetchContext   = FetchContextDummy(
    Map(projectRef -> project.context.copy(apiMappings = allApiMappings)),
    Set.empty,
    ProjectContextRejection
  )

  private val id             = nxv + "id"
  private val source         = NexusSource(jsonContentOf("resources/resource.json", "id" -> id))
  private val resourceSchema = nxv + "schema"

  private def assertSuccess(
      result: ResourceGenerationResult
  )(schema: Option[SchemaResource], expected: Resource)(implicit loc: Location) = {
    assertEquals(result.schema, schema)
    assertEquals(result.attempt.map(_.value), Right(expected))
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
      resolverContextResolution
    )
    for {
      expectedData <-
        ResourceGen.resourceAsync(id, projectRef, source.value, Revision(resourceSchema, defaultSchemaRevision))
      result       <- trial.generate(projectRef, resourceSchema, source)
    } yield {
      assertSuccess(result)(None, expectedData)
    }
  }

  test("Successfully generates a resource with a new schema") {
    val trial = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysValidate,
      fetchContext,
      resolverContextResolution
    )

    val anotherSchema = nxv + "anotherSchema"
    for {
      schemaSource <-
        ioJsonContentOf("resources/schema.json").map(_.addContext(contexts.shacl, contexts.schemasMetadata))
      schema       <- SchemaGen
                        .schemaAsync(anotherSchema, project.ref, schemaSource.removeKeys(keywords.id))
                        .map(SchemaGen.resourceFor(_))
      expectedData <-
        ResourceGen.resourceAsync(id, projectRef, source.value, Revision(anotherSchema, defaultSchemaRevision))
      result       <- trial.generate(projectRef, schema, source)
    } yield {
      assertSuccess(result)(Some(schema), expectedData)
    }
  }

  test("Fail when validation raises an error") {
    val expectedError = ReservedResourceId(id)
    val trial         = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysFail(expectedError),
      fetchContext,
      resolverContextResolution
    )

    assertError(trial.generate(projectRef, resourceSchema, source))(None, expectedError)
  }

  test("Validate a resource against a new schema reference") {
    val anotherSchema = nxv + "anotherSchema"
    val expected      = Validated(projectRef, Revision(anotherSchema, defaultSchemaRevision), defaultReport)
    for {
      resource <- ResourceGen
                    .resourceAsync(id, projectRef, source.value, Revision(resourceSchema, 1))
                    .map(ResourceGen.resourceFor(_))
      trial     = ResourcesTrial(
                    (_, _) => IO.pure(resource),
                    alwaysValidate,
                    fetchContext,
                    resolverContextResolution
                  )
      result   <- trial.validate(id, projectRef, Some(anotherSchema))
    } yield {
      assertEquals(result, expected)
    }
  }

  test("Validate a resource against its own schema") {
    for {
      resource <- ResourceGen
                    .resourceAsync(id, projectRef, source.value, Revision(resourceSchema, 1))
                    .map(ResourceGen.resourceFor(_))
      trial     = ResourcesTrial(
                    (_, _) => IO.pure(resource),
                    alwaysValidate,
                    fetchContext,
                    resolverContextResolution
                  )
      result   <- trial.validate(id, projectRef, None)
    } yield {
      assertEquals(result, Validated(projectRef, Revision(resourceSchema, defaultSchemaRevision), defaultReport))
    }
  }

  test("Fail to validate a resource against the specified schema") {
    val anotherSchema = nxv + "anotherSchema"
    for {
      resource     <- ResourceGen
                        .resourceAsync(id, projectRef, source.value, Revision(resourceSchema, 1))
                        .map(ResourceGen.resourceFor(_))
      expectedError =
        InvalidResource(id, Revision(anotherSchema, defaultSchemaRevision), defaultReport, resource.value.expanded)
      trial         = ResourcesTrial(
                        (_, _) => IO.pure(resource),
                        alwaysFail(expectedError),
                        fetchContext,
                        resolverContextResolution
                      )
      result       <- trial.validate(id, projectRef, Some(anotherSchema)).attempt
    } yield {
      assertEquals(result, Left(expectedError))
    }
  }

}
