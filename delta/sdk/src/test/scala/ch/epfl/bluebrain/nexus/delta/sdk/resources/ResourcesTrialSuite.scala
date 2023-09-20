package ch.epfl.bluebrain.nexus.delta.sdk.resources

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
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.resources.ValidationResult._
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.{Resource, ResourceGenerationResult, ResourceRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.{InvalidResource, ProjectContextRejection, ReservedResourceId}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Revision
import ch.epfl.bluebrain.nexus.testkit.bio.BioSuite
import ch.epfl.bluebrain.nexus.testkit.{IOFixedClock, TestHelpers}
import monix.bio.{IO, UIO}
import munit.Location

import java.util.UUID

class ResourcesTrialSuite extends BioSuite with ValidateResourceFixture with TestHelpers with IOFixedClock {

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

  private val fetchResourceFail = IO.terminate(new IllegalStateException("Should not be attempt to fetch a resource"))

  private val resolverContextResolution: ResolverContextResolution = new ResolverContextResolution(
    res,
    (_, _, _) => fetchResourceFail
  )

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
      io: UIO[ResourceGenerationResult]
  )(schema: Option[SchemaResource], result: Resource)(implicit loc: Location) =
    io.map { generated =>
      assertEquals(generated.schema, schema)
      assertEquals(generated.attempt.map(_.value), Right(result))
    }

  private def assertError(
      io: UIO[ResourceGenerationResult]
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

    val expectedData =
      ResourceGen.resource(id, projectRef, source.value, Revision(resourceSchema, defaultSchemaRevision))
    assertSuccess(trial.generate(projectRef, resourceSchema, source))(None, expectedData)
  }

  test("Successfully generates a resource with a new schema") {
    val trial = ResourcesTrial(
      (_, _) => fetchResourceFail,
      alwaysValidate,
      fetchContext,
      resolverContextResolution
    )

    val anotherSchema = nxv + "anotherSchema"
    val schemaSource  = jsonContentOf("resources/schema.json").addContext(contexts.shacl, contexts.schemasMetadata)
    val schema        = SchemaGen.resourceFor(
      SchemaGen.schema(anotherSchema, project.ref, schemaSource.removeKeys(keywords.id))
    )

    val expectedData =
      ResourceGen.resource(id, projectRef, source.value, Revision(anotherSchema, defaultSchemaRevision))
    assertSuccess(trial.generate(projectRef, schema, source))(Some(schema), expectedData)
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
    val resource      = ResourceGen.resourceFor(
      ResourceGen.resource(id, projectRef, source.value, Revision(resourceSchema, 1))
    )
    val anotherSchema = nxv + "anotherSchema"

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysValidate,
      fetchContext,
      resolverContextResolution
    )

    val expected = Validated(projectRef, Revision(anotherSchema, defaultSchemaRevision), defaultReport)
    trial.validate(id, projectRef, Some(anotherSchema)).assert(expected)
  }

  test("Validate a resource against its own schema") {
    val resource = ResourceGen.resourceFor(
      ResourceGen.resource(id, projectRef, source.value, Revision(resourceSchema, 1))
    )

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysValidate,
      fetchContext,
      resolverContextResolution
    )

    val expected = Validated(projectRef, Revision(resourceSchema, defaultSchemaRevision), defaultReport)
    trial.validate(id, projectRef, None).assert(expected)
  }

  test("Fail to validate a resource against the specified schema") {
    val resource      = ResourceGen.resourceFor(
      ResourceGen.resource(id, projectRef, source.value, Revision(resourceSchema, 1))
    )
    val anotherSchema = nxv + "anotherSchema"

    val expectedError =
      InvalidResource(id, Revision(anotherSchema, defaultSchemaRevision), defaultReport, resource.value.expanded)

    val trial = ResourcesTrial(
      (_, _) => IO.pure(resource),
      alwaysFail(expectedError),
      fetchContext,
      resolverContextResolution
    )

    trial.validate(id, projectRef, Some(anotherSchema)).error(expectedError)
  }

}
