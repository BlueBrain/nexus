package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.ExpandIri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverRejection.{InvalidResolution, InvalidResolvedResourceId, InvalidResolverResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.ResolverReport
import ch.epfl.bluebrain.nexus.delta.sdk.model.{IdSegment, IdSegmentRef}
import ch.epfl.bluebrain.nexus.delta.sdk.{Projects, ResolverResolution, Resolvers}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import monix.bio.IO

/**
  * Allow to attempt resolutions for the different resource types available
  * @param fetchProject
  *   how to fetch a project
  * @param resourceResolution
  *   the resource resolution
  */
final class MultiResolution(
    fetchProject: ProjectRef => IO[ResolverRejection, Project],
    resourceResolution: ResolverResolution[ReferenceExchangeValue[_]]
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
  )(implicit caller: Caller): IO[ResolverRejection, MultiResolutionResult[ResourceResolutionReport]] =
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
  )(implicit caller: Caller): IO[ResolverRejection, MultiResolutionResult[ResolverReport]] = {

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
    * @param projects
    *   a project instance
    * @param resourceResolution
    *   a resource resolution instance
    */
  def apply[A](
      projects: Projects,
      resourceResolution: ResolverResolution[ReferenceExchangeValue[_]]
  ): MultiResolution =
    new MultiResolution(projects.fetchProject[ResolverRejection], resourceResolution)

}
