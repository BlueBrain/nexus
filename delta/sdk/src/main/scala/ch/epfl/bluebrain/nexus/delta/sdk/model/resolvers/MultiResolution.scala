package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceType.{DataResource, SchemaResource}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{InvalidResolution, InvalidResolvedResourceId, InvalidResolverResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, ResourceRef, ResourceType}
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, Resolvers, ResourceResolution}
import monix.bio.IO

/**
  * Allow to attempt resolutions first as a [[Resource]] and then as a [[Schema]] successively
  * @param fetchProject        how to fetch a project
  * @param resourceResolution  the resource resolution
  * @param schemaResolution    the resource resolution
  */
final class MultiResolution(
    fetchProject: ProjectRef => IO[ResolverRejection, Project],
    resourceResolution: ResourceResolution[Resource],
    schemaResolution: ResourceResolution[Schema]
) {

  private val expandResourceIri = new ExpandIri(InvalidResolvedResourceId.apply)

  /**
    * Attempts to resolve the resourceId against all active resolvers of the given project
    * @param resourceSegment  the resource id to resolve
    * @param projectRef       the project from which we try to resolve
    */
  def apply(resourceSegment: IdSegment, projectRef: ProjectRef)(implicit
      caller: Caller
  ): IO[ResolverRejection, MultiResolutionResult[ResourceResolutionReport]] =
    for {
      project    <- fetchProject(projectRef)
      resourceId <- expandResourceIri(resourceSegment, project)
      resourceRef = ResourceRef(resourceId)
      result     <- resourceResolution.resolveReport(resourceRef, projectRef).flatMap {
                      case (resourceReport, Some(resourceResult)) =>
                        IO.pure(MultiResolutionResult.resource(resourceResult, DataResource -> resourceReport))
                      case (resourceReport, None)                 =>
                        schemaResolution.resolveReport(resourceRef, projectRef).flatMap {
                          case (schemaReport, Some(schemaResult)) =>
                            IO.pure(
                              MultiResolutionResult.schema(
                                schemaResult,
                                DataResource   -> resourceReport,
                                SchemaResource -> schemaReport
                              )
                            )
                          case (schemaReport, None)               =>
                            IO.raiseError(
                              InvalidResolution(
                                resourceId,
                                projectRef,
                                Map(DataResource -> resourceReport, SchemaResource -> schemaReport)
                              )
                            )
                        }
                    }
    } yield result

  /**
    * Attempts to resolve the resourceId against the given resolver of the given project
    * @param resourceSegment   the resource id to resolve
    * @param projectRef     the project from which we try to resolve
    * @param resolverSegment  the resolver to use specifically
    */
  def apply(resourceSegment: IdSegment, projectRef: ProjectRef, resolverSegment: IdSegment)(implicit
      caller: Caller
  ): IO[ResolverRejection, MultiResolutionResult[ResolverReport]] = {

    for {
      project    <- fetchProject(projectRef)
      resourceId <- expandResourceIri(resourceSegment, project)
      resourceRef = ResourceRef(resourceId)
      resolverId <- Resolvers.expandIri(resolverSegment, project)
      result     <- resourceResolution.resolveReport(resourceRef, projectRef, resolverId).flatMap {
                      case (resourceReport, Some(resourceResult)) =>
                        IO.pure(MultiResolutionResult.resource(resourceResult, ResourceType.DataResource -> resourceReport))
                      case (resourceReport, None)                 =>
                        schemaResolution.resolveReport(resourceRef, projectRef, resolverId).flatMap {
                          case (schemaReport, Some(schemaResult)) =>
                            IO.pure(
                              MultiResolutionResult
                                .schema(schemaResult, DataResource -> resourceReport, SchemaResource -> schemaReport)
                            )
                          case (schemaReport, None)               =>
                            IO.raiseError(
                              InvalidResolverResolution(
                                resourceId,
                                resolverId,
                                projectRef,
                                Map(DataResource -> resourceReport, SchemaResource -> schemaReport)
                              )
                            )
                        }
                    }
    } yield result

  }

}

object MultiResolution {

  /**
    * Create a multi resolution instance
    * @param projects           a project instance
    * @param resourceResolution a [[Resource]] resolution instance
    * @param schemaResolution   a [[Schema]] resolution instance
    */
  def apply(
      projects: Projects,
      resourceResolution: ResourceResolution[Resource],
      schemaResolution: ResourceResolution[Schema]
  ): MultiResolution =
    new MultiResolution(
      projects.fetchProject[ResolverRejection],
      resourceResolution,
      schemaResolution
    )

}
