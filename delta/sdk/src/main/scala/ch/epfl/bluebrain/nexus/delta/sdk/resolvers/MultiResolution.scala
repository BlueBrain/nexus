package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.{ExpandIri, JsonLdContent}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverRejection.{InvalidResolution, InvalidResolvedResourceId, InvalidResolverResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{MultiResolutionResult, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Allow to attempt resolutions for the different resource types available
  * @param fetchProject
  *   how to fetch a project
  * @param resourceResolution
  *   the resource resolution
  */
final class MultiResolution(
    fetchProject: ProjectRef => IO[ProjectContext],
    resourceResolution: ResolverResolution[JsonLdContent[_, _]]
) {

  private val expandResourceIri = new ExpandIri(InvalidResolvedResourceId.apply)

  /**
    * Attempts to resolve the resourceId against all active resolvers of the given project
    *
    * @param resourceSegment
    *   the resource id to resolve with its optional rev/tag
    * @param projectRef
    *   the project from which we try to resolve
    */
  def apply(
      resourceSegment: IdSegmentRef,
      projectRef: ProjectRef
  )(implicit caller: Caller): IO[MultiResolutionResult[ResourceResolutionReport]] =
    for {
      project     <- fetchProject(projectRef)
      resourceRef <- expandResourceIri(resourceSegment, project)
      result      <- resourceResolution.resolveReport(resourceRef, projectRef).flatMap {
                       case (resourceReport, Some(resourceResult)) =>
                         IO.pure(MultiResolutionResult(resourceReport, resourceResult))
                       case (resourceReport, None)                 =>
                         IO.raiseError(InvalidResolution(resourceRef, projectRef, resourceReport))
                     }
    } yield result

  /**
    * Attempts to resolve the resourceId against the given resolver of the given project
    * @param resourceSegment
    *   the resource id to resolve with its optional rev/tag
    * @param projectRef
    *   the project from which we try to resolve
    * @param resolverSegment
    *   the resolver to use specifically
    */
  def apply(
      resourceSegment: IdSegmentRef,
      projectRef: ProjectRef,
      resolverSegment: IdSegment
  )(implicit caller: Caller): IO[MultiResolutionResult[ResolverReport]] = {

    for {
      project     <- fetchProject(projectRef)
      resourceRef <- expandResourceIri(resourceSegment, project)
      resolverId  <- Resolvers.expandIri(resolverSegment, project)
      result      <- resourceResolution.resolveReport(resourceRef, projectRef, resolverId).flatMap {
                       case (resourceReport, Some(resourceResult)) =>
                         IO.pure(MultiResolutionResult(resourceReport, resourceResult))
                       case (resourceReport, None)                 =>
                         IO.raiseError(InvalidResolverResolution(resourceRef, resolverId, projectRef, resourceReport))
                     }
    } yield result

  }

}

object MultiResolution {

  /**
    * Create a multi resolution instance
    * @param fetchContext
    *   to fetch the project context
    * @param resourceResolution
    *   a resource resolution instance
    */
  def apply(
      fetchContext: FetchContext,
      resourceResolution: ResolverResolution[JsonLdContent[_, _]]
  ): MultiResolution =
    new MultiResolution(fetchContext.onRead, resourceResolution)

}
