package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.Fetch
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{DeprecationCheck, ResolverResolutionResult}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.{ResolverFailedReport, ResolverReport, ResolverSuccessReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Resolver, ResolverRejection, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}

import scala.collection.immutable.VectorMap

/**
  * Resolution for a given type of resource
  * @param checkAcls
  *   how to fetch all acls
  * @param fetchResolver
  *   how to fetch a resolver by id and project
  * @param listResolvers
  *   list all non-deprecated resolvers for a project
  * @param fetch
  *   how we can get a resource from a [[ResourceRef]]
  */
final class ResolverResolution[R](
    checkAcls: (ProjectRef, Set[Identity]) => IO[Boolean],
    listResolvers: ProjectRef => IO[List[Resolver]],
    fetchResolver: (Iri, ProjectRef) => IO[Resolver],
    fetch: (ResourceRef, ProjectRef) => Fetch[R],
    extractTypes: R => Set[Iri],
    deprecationCheck: DeprecationCheck[R]
) {

  /**
    * Attempts to resolve the resource against all resolvers of the given project by priority order, discards the report
    * when it succeeds, raises it as an error if resolution fails
    *
    * @param ref
    *   the resource reference
    * @param projectRef
    *   the project reference
    */
  def resolve(ref: ResourceRef, projectRef: ProjectRef)(implicit
      caller: Caller
  ): IO[Either[ResourceResolutionReport, R]] =
    resolveReport(ref, projectRef).map { case (report, resource) =>
      resource.toRight(report)
    }

  /**
    * Attempts to resolve the resource against the given resolver and return the resource if found and a report of how
    * the resolution went
    *
    * @param resource
    *   the resource reference
    * @param project
    *   the project reference
    */
  def resolveReport(resource: ResourceRef, project: ProjectRef)(implicit
      caller: Caller
  ): IO[(ResourceResolutionReport, Option[R])] = {
    val initial: (ResourceResolutionReport, Option[R]) =
      ResourceResolutionReport() -> None

    listResolvers(project)
      .flatMap { resolvers =>
        resolvers
          .sortBy { r => (r.value.priority.value, r.id.toString) }
          .foldLeftM(initial) { (previous, resolver) =>
            previous match {
              // A resolver was able to get the resource, we keep the result
              case (report, result @ Some(_)) => IO.pure(report -> result)
              // No resolution was successful yet, we carry on
              case (report, None)             =>
                resolveReport(resource, project, resolver).map { case (resolverReport, result) =>
                  report.copy(history = report.history :+ resolverReport) -> result
                }
            }
          }
      }
  }

  /**
    * Attempts to resolve the resource against the specified resolver, discards the report when it succeeds, raises it
    * as an error if resolution fails
    *
    * @param ref
    *   the resource reference
    * @param projectRef
    *   the project reference
    */
  def resolve(ref: ResourceRef, projectRef: ProjectRef, resolverId: Iri)(implicit
      caller: Caller
  ): IO[Either[ResolverReport, R]]   =
    resolveReport(ref, projectRef, resolverId)
      .map { case (report, resource) => resource.toRight(report) }

  /**
    * Attempts to resolve the resource against the given resolver and return the resource if found and a report of how
    * the resolution went
    * @param ref
    *   the resource reference
    * @param projectRef
    *   the project reference
    * @param resolverId
    *   the resolver identifier
    */
  def resolveReport(ref: ResourceRef, projectRef: ProjectRef, resolverId: Iri)(implicit
      caller: Caller
  ): IO[(ResolverReport, Option[R])] =
    fetchResolver(resolverId, projectRef)
      .flatMap { r => resolveReport(ref, projectRef, r) }
      .recover { case r: ResolverRejection =>
        ResolverReport.failed(resolverId, projectRef -> WrappedResolverRejection(r)) -> None
      }

  private def resolveReport(
      ref: ResourceRef,
      projectRef: ProjectRef,
      resolver: Resolver
  )(implicit caller: Caller): IO[ResolverResolutionResult[R]] =
    resolver match {
      case i: InProjectResolver    => inProjectResolve(ref, projectRef, i)
      case c: CrossProjectResolver => crossProjectResolve(ref, c)
    }

  private def inProjectResolve(
      ref: ResourceRef,
      projectRef: ProjectRef,
      resolver: InProjectResolver
  ): IO[ResolverResolutionResult[R]] =
    for {
      resourceOpt <- fetch(ref, projectRef)
      result      <- resourceOpt.traverse(runDeprecationCheck(ref, projectRef, _))
    } yield {
      result match {
        // The resource has not been found in the project
        case None        => ResolverReport.failed(resolver.id, projectRef -> ResolutionFetchRejection(ref, projectRef)) -> None
        // The resource exists but the deprecation check is positive so we reject it
        case Some(true)  =>
          ResolverReport.failed(resolver.id, projectRef -> ResourceIsDeprecated(ref.original, projectRef)) -> None
        // The resource has been successfully resolved
        case Some(false) => ResolverReport.success(resolver.id, projectRef)                                             -> resourceOpt
      }
    }

  private def crossProjectResolve(
      ref: ResourceRef,
      resolver: CrossProjectResolver
  )(implicit caller: Caller): IO[ResolverResolutionResult[R]] = {
    import resolver.value._

    def fetchInProject(p: ProjectRef) = fetch(ref, p).flatMap(
      IO.fromOption(_)(ResolutionFetchRejection(ref, p))
    )

    def validateIdentities(p: ProjectRef): IO[Unit] = {
      val identities = identityResolution match {
        case UseCurrentCaller               => caller.identities
        case ProvidedIdentities(identities) => identities
      }

      checkAcls(p, identities).flatMap {
        case true  => IO.unit
        case false => IO.raiseError(ProjectAccessDenied(p, identityResolution))
      }
    }

    def validateResourceTypes(types: Set[Iri], p: ProjectRef): IO[Unit] =
      IO.raiseUnless(resourceTypes.isEmpty || resourceTypes.exists(types.contains))(ResourceTypesDenied(p, types))

    def validateDeprecationCheck(p: ProjectRef, resource: R) =
      runDeprecationCheck(ref, p, resource).flatMap { isDeprecated =>
        IO.raiseWhen(isDeprecated)(ResourceIsDeprecated(ref.original, p))
      }

    val initial: ResolverResolutionResult[R] = ResolverFailedReport(resolver.id, VectorMap.empty) -> None
    projects.foldLeftM(initial) { (previous, projectRef) =>
      previous match {
        // We were able to resolve with this resolver, we keep that result
        case (s: ResolverSuccessReport, r) => IO.pure(s -> r)
        // No resolution was successful yet, we carry on
        case (f: ResolverFailedReport, _)  =>
          val resolve = for {
            _        <- validateIdentities(projectRef)
            resource <- fetchInProject(projectRef)
            _        <- validateResourceTypes(extractTypes(resource), projectRef)
            _        <- validateDeprecationCheck(projectRef, resource)
          } yield ResolverSuccessReport(resolver.id, projectRef, f.rejections) -> Option(resource)
          resolve.attemptNarrow[ResolverResolutionRejection].map {
            case Left(r)  => f.copy(rejections = f.rejections + (projectRef -> r)) -> None
            case Right(s) => s
          }
      }
    }
  }

  private def runDeprecationCheck(resourceRef: ResourceRef, project: ProjectRef, resource: R): IO[Boolean] = {
    if (deprecationCheck.enabled) {
      resourceRef match {
        case _: ResourceRef.Latest => IO.pure(deprecationCheck(resource))
        // Fetch the latest version to get its deprecation status
        case _                     => fetch(ResourceRef.Latest(resourceRef.original), project).map(_.exists(deprecationCheck(_)))
      }
    } else IO.pure(false)
  }
}

object ResolverResolution {

  /**
    * Alias when resolving a [[ResourceF]]
    */
  type ResourceResolution[R] = ResolverResolution[ResourceF[R]]

  type ResolverResolutionResult[R] = (ResolverReport, Option[R])

  /**
    * Allows to check and exclude deprecated resources from the resolution
    * @param enabled
    *   if the check is enabled
    * @param isDeprecated
    *   extract the deprecation status from the resource
    */
  final case class DeprecationCheck[R](enabled: Boolean, isDeprecated: R => Boolean) {
    def apply(r: R): Boolean = isDeprecated(r)
  }

  /**
    * Resolution for a given type based on resolvers
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param fetch
    *   how to fetch the resource
    * @param extractTypes
    *   how to extract resource types from an R
    * @param readPermission
    *   the mandatory permission
    */
  def apply[R](
      aclCheck: AclCheck,
      resolvers: Resolvers,
      fetch: (ResourceRef, ProjectRef) => Fetch[R],
      extractTypes: R => Set[Iri],
      readPermission: Permission,
      deprecationCheck: DeprecationCheck[R]
  ) = {
    def fetchActiveResolvers(project: ProjectRef) = resolvers
      .list(project)
      .map { r =>
        r.results.mapFilter { r => Option.unless(r.source.deprecated)(r.source.value) }.toList
      }
    new ResolverResolution(
      checkAcls = (p: ProjectRef, identities: Set[Identity]) => aclCheck.authorizeFor(p, readPermission, identities),
      listResolvers = fetchActiveResolvers,
      fetchResolver = (id: Iri, projectRef: ProjectRef) => resolvers.fetchActiveResolver(id, projectRef),
      fetch = fetch,
      extractTypes,
      deprecationCheck
    )
  }

  def apply(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      fetch: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[_, _]]],
      excludeDeprecated: Boolean
  ): ResolverResolution[JsonLdContent[_, _]] = {
    val deprecationCheck = DeprecationCheck[JsonLdContent[_, _]](excludeDeprecated, _.resource.deprecated)
    apply(aclCheck, resolvers, fetch, _.resource.types, Permissions.resources.read, deprecationCheck)
  }

}
