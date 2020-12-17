package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.generators.{ProjectGen, ResourceGen, ResourceResolutionGen, SchemaGen}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.{IriSegment, StringSegment}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceType.{DataResource, SchemaResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{InvalidResolution, InvalidResolverId, InvalidResolverResolution, WrappedProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{DataResource, SchemaResource}
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, IOValues, TestHelpers}
import monix.bio.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultiResolutionSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues with CirceLiteral {

  private val alice                = User("alice", Label.unsafe("wonderland"))
  implicit val aliceCaller: Caller = Caller(User("alice", Label.unsafe("wonderland")), Set(alice))

  private val projectRef = ProjectRef.unsafe("org", "project")

  private val shaclResolvedCtx              = jsonContentOf("contexts/shacl.json")
  implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

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

  private val unknownResourceId = nxv + "xxx"

  def fetchResource: (ResourceRef, ProjectRef) => IO[ResourceNotFound, DataResource] =
    (ref: ResourceRef, p: ProjectRef) =>
      ref match {
        case Latest(i) if i == resourceId => IO.pure(resourceFR)
        case _                            => IO.raiseError(ResourceNotFound(ref.iri, p))
      }

  def fetchSchema: (ResourceRef, ProjectRef) => IO[ResourceNotFound, SchemaResource] =
    (ref: ResourceRef, p: ProjectRef) =>
      ref match {
        case Latest(i) if i == schemaId => IO.pure(resourceFS)
        case _                          => IO.raiseError(ResourceNotFound(ref.iri, p))
      }

  private val project = ProjectGen.project("org", "project")

  def fetchProject: ProjectRef => IO[ResolverRejection, Project] =
    (ref: ProjectRef) =>
      ref match {
        case `projectRef` => IO.pure(project)
        case _            => IO.raiseError(WrappedProjectRejection(ProjectNotFound(ref)))
      }

  private val resolverId = nxv + "in-project"

  private val resourceResolution = ResourceResolutionGen.singleInProject(projectRef, fetchResource)

  private val schemaResolution = ResourceResolutionGen.singleInProject(projectRef, fetchSchema)

  private val multiResolution = new MultiResolution(fetchProject, resourceResolution, schemaResolution)

  "A multi-resolution" should {

    "resolve the id as a resource" in {
      multiResolution(IriSegment(resourceId), projectRef).accepted shouldEqual MultiResolutionResult.resource(
        resourceFR,
        DataResource -> ResourceResolutionReport(ResolverReport.success(resolverId))
      )
    }

    "resolve the id as a resource with a specific resolver" in {
      multiResolution(
        IriSegment(resourceId),
        projectRef,
        IriSegment(resolverId)
      ).accepted shouldEqual MultiResolutionResult
        .resource(
          resourceFR,
          DataResource -> ResolverReport.success(resolverId)
        )
    }

    "resolve the id as a schema" in {
      multiResolution(IriSegment(schemaId), projectRef).accepted shouldEqual MultiResolutionResult.schema(
        resourceFS,
        DataResource   -> ResourceResolutionReport(
          ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(schemaId, projectRef))
        ),
        SchemaResource -> ResourceResolutionReport(ResolverReport.success(resolverId))
      )
    }

    "resolve the id as a schema with a specific resolver" in {
      multiResolution(
        IriSegment(schemaId),
        projectRef,
        IriSegment(resolverId)
      ).accepted shouldEqual MultiResolutionResult.schema(
        resourceFS,
        DataResource   -> ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(schemaId, projectRef)),
        SchemaResource -> ResolverReport.success(resolverId)
      )
    }

    "fail when it can't be resolved neither as a resource or a schema" in {
      multiResolution(IriSegment(unknownResourceId), projectRef).rejected shouldEqual InvalidResolution(
        unknownResourceId,
        projectRef,
        Map(
          DataResource   -> ResourceResolutionReport(
            ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
          ),
          SchemaResource -> ResourceResolutionReport(
            ResolverReport.failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef))
          )
        )
      )
    }

    "fail with a specific resolver when it can't be resolved neither as a resource or a schema" in {
      multiResolution(
        IriSegment(unknownResourceId),
        projectRef,
        IriSegment(resolverId)
      ).rejected shouldEqual InvalidResolverResolution(
        unknownResourceId,
        resolverId,
        projectRef,
        Map(
          DataResource   -> ResolverReport
            .failed(resolverId, projectRef -> ResourceNotFound(unknownResourceId, projectRef)),
          SchemaResource -> ResolverReport.failed(
            resolverId,
            projectRef -> ResourceNotFound(unknownResourceId, projectRef)
          )
        )
      )
    }

    "fail with an invalid resolver id" in {
      val invalid = "qa$%"
      multiResolution(
        IriSegment(resourceId),
        projectRef,
        StringSegment(invalid)
      ).rejected shouldEqual InvalidResolverId(invalid)
    }
  }

}
