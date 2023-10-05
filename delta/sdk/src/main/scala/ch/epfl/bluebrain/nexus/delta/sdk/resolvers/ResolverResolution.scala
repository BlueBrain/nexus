package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.effect.migration._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.JsonLdContent
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{Fetch, ResolverResolutionResult}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResolverResolutionRejection.{ProjectAccessDenied, ResolutionFetchRejection, ResourceTypesDenied, WrappedResolverRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.ResourceResolutionReport.{ResolverFailedReport, ResolverReport, ResolverSuccessReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Resolver, ResolverRejection, ResolverResolutionRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.{ResolverResource, ResourceShifts}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}
import monix.bio.{IO => BIO}

import java.time.Instant
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
    extractTypes: R => Set[Iri]
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
    * @param ref
    *   the resource reference
    * @param projectRef
    *   the project reference
    */
  def resolveReport(ref: ResourceRef, projectRef: ProjectRef)(implicit
      caller: Caller
  ): IO[(ResourceResolutionReport, Option[R])] = {
    val initial: (ResourceResolutionReport, Option[R]) =
      ResourceResolutionReport() -> None

    listResolvers(projectRef)
      .flatMap { resolvers =>
        resolvers
          .sortBy { r => (r.value.priority.value, r.id.toString) }
          .foldLeftM(initial) { (previous, resolver) =>
            previous match {
              // A resolver was able to get the resource, we keep the result
              case (report, result @ Some(_)) => IO.pure(report -> result)
              // No resolution was successful yet, we carry on
              case (report, None)             =>
                resolveReport(ref, projectRef, resolver).map { case (resolverReport, result) =>
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
    fetch(ref, projectRef).map {
      case None => ResolverReport.failed(resolver.id, projectRef -> ResolutionFetchRejection(ref, projectRef)) -> None
      case s    => ResolverReport.success(resolver.id, projectRef)                                             -> s
    }

  private def crossProjectResolve(
      ref: ResourceRef,
      resolver: CrossProjectResolver
  )(implicit caller: Caller): IO[ResolverResolutionResult[R]] = {
    import resolver.value._

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

    val initial: ResolverResolutionResult[R] = ResolverFailedReport(resolver.id, VectorMap.empty) -> None
    projects.foldLeftM(initial) { (previous, projectRef) =>
      previous match {
        // We were able to resolve with this resolver, we keep that result
        case (s: ResolverSuccessReport, r) => IO.pure(s -> r)
        // No resolution was successful yet, we carry on
        case (f: ResolverFailedReport, _)  =>
          val resolve = for {
            _        <- validateIdentities(projectRef)
            resource <- fetch(ref, projectRef).flatMap { res =>
                          IO.fromOption(res)(ResolutionFetchRejection(ref, projectRef))
                        }
            _        <- validateResourceTypes(extractTypes(resource), projectRef)
          } yield ResolverSuccessReport(resolver.id, projectRef, f.rejections) -> Option(resource)
          resolve.attemptNarrow[ResolverResolutionRejection].map {
            case Left(r)  => f.copy(rejections = f.rejections + (projectRef -> r)) -> None
            case Right(s) => s
          }
      }
    }
  }
}

object ResolverResolution {

  /**
    * Alias when resolving a [[ResourceF]]
    */
  type ResourceResolution[R] = ResolverResolution[ResourceF[R]]

  type Fetch[R] = IO[Option[R]]

  type FetchResource[R] = IO[Option[ResourceF[R]]]

  type ResolverResolutionResult[R] = (ResolverReport, Option[R])

  private val resolverSearchParams = ResolverSearchParams(deprecated = Some(false), filter = _ => BIO.pure(true))

  private val resolverOrdering: Ordering[ResolverResource] = Ordering[Instant] on (r => r.createdAt)

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
      readPermission: Permission
  ) = new ResolverResolution(
    checkAcls = (p: ProjectRef, identities: Set[Identity]) => aclCheck.authorizeFor(p, readPermission, identities),
    listResolvers = (projectRef: ProjectRef) =>
      resolvers
        .list(projectRef, Pagination.OnePage, resolverSearchParams, resolverOrdering)
        .map { r => r.results.map { r: ResultEntry[ResolverResource] => r.source.value }.toList },
    fetchResolver = (id: Iri, projectRef: ProjectRef) => resolvers.fetchActiveResolver(id, projectRef),
    fetch = fetch,
    extractTypes
  )

  /**
    * Resolution based on resolvers and reference exchanges
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param shifts
    *   how to fetch the resource
    */
  def apply(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      shifts: ResourceShifts
  ): ResolverResolution[JsonLdContent[_, _]] =
    apply(aclCheck, resolvers, shifts.fetch, _.resource.types, Permissions.resources.read)

  def apply(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      fetch: (ResourceRef, ProjectRef) => IO[Option[JsonLdContent[_, _]]]
  ): ResolverResolution[JsonLdContent[_, _]] =
    apply(aclCheck, resolvers, fetch, _.resource.types, Permissions.resources.read)

}
