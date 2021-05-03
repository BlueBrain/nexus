package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResolverResolutionGen, ResourceGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.{Latest, Revision}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{InvalidResolution, InvalidResolverId, InvalidResolverResolution, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.Fixtures
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import io.circe.Json
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultiResolutionSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with CirceLiteral
    with Fixtures {

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(User("alice", Label.unsafe("wonderland")), Set(alice))

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

  private def referenceExchangeValue[R](resource: ResourceF[R], source: Json)(implicit enc: JsonLdEncoder[R]) =
    ReferenceExchangeValue(resource, source, enc)

  private val resourceValue = referenceExchangeValue(resourceFR, resourceFR.value.source)
  private val schemaValue   = referenceExchangeValue(resourceFS, resourceFS.value.source)

  def fetch: (ResourceRef, ProjectRef) => Fetch[ReferenceExchangeValue[_]] =
    (ref: ResourceRef, _: ProjectRef) =>
      ref match {
        case Latest(i) if i == resourceId       => IO.some(resourceValue)
        case Revision(_, i, _) if i == schemaId => IO.some(schemaValue)
        case _                                  => IO.none
      }

  private val project = ProjectGen.project("org", "project")

  def fetchProject: ProjectRef => IO[ResolverRejection, Project] =
    (ref: ProjectRef) =>
      ref match {
        case `projectRef` => IO.pure(project)
        case _            => IO.raiseError(WrappedProjectRejection(ProjectNotFound(ref)))
      }

  private val resolverId = nxv + "in-project"

  private val resourceResolution = ResolverResolutionGen.singleInProject(projectRef, fetch)

  private val multiResolution = new MultiResolution(fetchProject, resourceResolution)

  "A multi-resolution" should {

    "resolve the id as a resource" in {
      multiResolution(resourceId, projectRef, None).accepted shouldEqual MultiResolutionResult(
        ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)),
        resourceValue
      )
    }

    "resolve the id as a resource with a specific resolver" in {
      multiResolution(
        resourceId,
        projectRef,
        None,
        resolverId
      ).accepted shouldEqual MultiResolutionResult(ResolverReport.success(resolverId, projectRef), resourceValue)
    }

    "resolve the id as a schema" in {
      multiResolution(schemaId, projectRef, Some(Left(5L))).accepted shouldEqual MultiResolutionResult(
        ResourceResolutionReport(ResolverReport.success(resolverId, projectRef)),
        schemaValue
      )
    }

    "resolve the id as a schema with a specific resolver" in {
      multiResolution(
        schemaId,
        projectRef,
        Some(Left(5L)),
        resolverId
      ).accepted shouldEqual MultiResolutionResult(
        ResolverReport.success(resolverId, projectRef),
        schemaValue
      )
    }

    "fail when it can't be resolved neither as a resource or a schema" in {
      multiResolution(unknownResourceId, projectRef, None).rejected shouldEqual InvalidResolution(
        unknownResourceRef,
        projectRef,
        ResourceResolutionReport(
          ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
        )
      )
    }

    "fail with a specific resolver when it can't be resolved neither as a resource or a schema" in {
      multiResolution(
        unknownResourceId,
        projectRef,
        None,
        resolverId
      ).rejected shouldEqual InvalidResolverResolution(
        unknownResourceRef,
        resolverId,
        projectRef,
        ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
      )
    }

    "fail with an invalid resolver id" in {
      val invalid = "qa$%"
      multiResolution(
        resourceId,
        projectRef,
        None,
        invalid
      ).rejected shouldEqual InvalidResolverId(invalid)
    }
  }

}
