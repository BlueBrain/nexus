package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ResolverResolutionGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegmentRef, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.{ApiMappings, ProjectContext}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{InvalidResolution, InvalidResolverResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{MultiResolutionResult, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.User
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

class MultiResolutionSuite extends NexusSuite with Fixtures {

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(alice, Set(alice))

  private val projectRef = ProjectRef.unsafe("org", "project")

  private val resourceId = nxv + "resource"
  private val resource   =
    ResourceGen.resource(resourceId, projectRef, jsonContentOf("resources/resource.json", "id" -> resourceId))
  private val resourceFR = ResourceGen.resourceFor(resource)

  private val schemaId   = nxv + "schemaId"
  private val schema     = SchemaGen.schema(
    schemaId,
    projectRef,
    jsonContentOf("resources/schema.json") deepMerge json"""{"@id": "$schemaId"}"""
  )
  private val resourceFS = SchemaGen.resourceFor(schema)

  private val unknownResourceId  = nxv + "xxx"
  private val unknownResourceRef = Latest(unknownResourceId)

  private def content[R](resource: ResourceF[R], source: Json)(implicit enc: JsonLdEncoder[R]) =
    JsonLdContent(resource, source, None)

  private val resourceValue = content(resourceFR, resourceFR.value.source)
  private val schemaValue   = content(resourceFS, resourceFS.value.source)

  def fetch: (ResourceRef, ProjectRef) => Fetch[JsonLdContent[_, _]] =
    (ref: ResourceRef, _: ProjectRef) =>
      ref match {
        case Latest(`resourceId`)       => IO.pure(Some(resourceValue))
        case Revision(_, `schemaId`, _) => IO.pure(Some(schemaValue))
        case _                          => IO.none
      }

  def fetchProject: ProjectRef => IO[ProjectContext] =
    FetchContextDummy(
      Map(projectRef -> ProjectContext.unsafe(ApiMappings.empty, nxv.base, nxv.base, enforceSchema = false))
    ).onRead

  private val resolverId = nxv + "in-project"

  private val resourceResolution = ResolverResolutionGen.singleInProject(projectRef, fetch)

  private val multiResolution = new MultiResolution(fetchProject, resourceResolution)

  test("Resolve the id as a resource") {
    val expected =
      MultiResolutionResult(ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)), resourceValue)
    multiResolution(resourceId, projectRef).assertEquals(expected)
  }

  test("Resolve the id as a resource with a specific resolver") {
    val expected = MultiResolutionResult(ResolverReport.success(resolverId, projectRef), resourceValue)
    multiResolution(resourceId, projectRef, resolverId).assertEquals(expected)
  }

  test("Resolve the id as a schema") {
    val expected =
      MultiResolutionResult(ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)), schemaValue)
    multiResolution(IdSegmentRef(schemaId, 5), projectRef).assertEquals(expected)
  }

  test("Resolve the id as a schema with a specific resolver") {
    val expected = MultiResolutionResult(ResolverReport.success(resolverId, projectRef), schemaValue)
    multiResolution(IdSegmentRef(schemaId, 5), projectRef, resolverId).assertEquals(expected)
  }

  test("Fail when it can't be resolved neither as a resource or a schema") {
    val expectedError = InvalidResolution(
      unknownResourceRef,
      projectRef,
      ResourceResolutionReport(
        ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
      )
    )
    multiResolution(unknownResourceId, projectRef).interceptEquals(expectedError)
  }

  test("Fail with a specific resolver when it can't be resolved neither as a resource or a schema") {
    val expectedError = InvalidResolverResolution(
      unknownResourceRef,
      resolverId,
      projectRef,
      ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
    )
    multiResolution(unknownResourceId, projectRef, resolverId).interceptEquals(expectedError)
  }
}
