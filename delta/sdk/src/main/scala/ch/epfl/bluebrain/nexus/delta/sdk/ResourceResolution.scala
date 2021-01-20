package ch.epfl.bluebrain.nexus.delta.sdk

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ResourceResolution.{FetchResource, ResolverResolutionResult}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddressFilter.AnyOrganizationAnyProject
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{Caller, Identity}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.IdentityResolution.{ProvidedIdentities, UseCurrentCaller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver.{CrossProjectResolver, InProjectResolver}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResolverResolutionRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.ResourceResolutionReport.{ResolverFailedReport, ResolverReport, ResolverSuccessReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Resolver, ResolverRejection, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.ResolverSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, ResultEntry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import monix.bio.{IO, UIO}

import scala.collection.immutable.VectorMap

/**
  * Resolution for a given type of resource
  * @param fetchAllAcls    how to fetch all acls
  * @param fetchResolver   how to fetch a resolver by id and project
  * @param listResolvers   list all non-deprecated resolvers for a project
  * @param readPermission  the permission required to get the given type of resource on a project
  * @param fetchResource   how we can get a resource from a [[ResourceRef]]
  */
final class ResourceResolution[R](
    fetchAllAcls: UIO[AclCollection],
    listResolvers: ProjectRef => UIO[List[Resolver]],
    fetchResolver: (Iri, ProjectRef) => IO[ResolverRejection, Resolver],
    fetchResource: (ResourceRef, ProjectRef) => FetchResource[R],
    readPermission: Permission
) {

  /**
    * Attempts to resolve the resource against all resolvers of the given project by priority order,
    * discards the report when it succeeds, raises it as an error if resolution fails
    *
    * @param ref        the resource reference
    * @param projectRef the project reference
    */
  def resolve(ref: ResourceRef, projectRef: ProjectRef)(implicit
      caller: Caller
  ): IO[ResourceResolutionReport, ResourceF[R]] =
    resolveReport(ref, projectRef).flatMap { case (report, resource) =>
      IO.fromOption(resource, report)
    }

  /**
    * Attempts to resolve the resource against the given resolver and
    * return the resource if found and a report of how the resolution went
    *
    * @param ref        the resource reference
    * @param projectRef the project reference
    */
  def resolveReport(ref: ResourceRef, projectRef: ProjectRef)(implicit
      caller: Caller
  ): UIO[(ResourceResolutionReport, Option[ResourceF[R]])] = {
    val initial: (ResourceResolutionReport, Option[ResourceF[R]]) =
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
    * Attempts to resolve the resource against the specified resolver,
    * discards the report when it succeeds, raises it as an error if resolution fails
    *
    * @param ref        the resource reference
    * @param projectRef the project reference
    */
  def resolve(ref: ResourceRef, projectRef: ProjectRef, resolverId: Iri)(implicit
      caller: Caller
  ): IO[ResolverReport, ResourceF[R]] =
    resolveReport(ref, projectRef, resolverId)
      .flatMap { case (report, resource) =>
        IO.fromOption(resource, report)
      }

  /**
    * Attempts to resolve the resource against the given resolver and return the resource if found and a report of how the resolution went
    * @param ref         the resource reference
    * @param projectRef  the project  reference
    * @param resolverId  the resolver identifier
    */
  def resolveReport(ref: ResourceRef, projectRef: ProjectRef, resolverId: Iri)(implicit
      caller: Caller
  ): UIO[(ResolverReport, Option[ResourceF[R]])] =
    fetchResolver(resolverId, projectRef)
      .flatMap { r => resolveReport(ref, projectRef, r) }
      .onErrorHandle { r =>
        ResolverReport.failed(resolverId, projectRef -> WrappedResolverRejection(r)) -> None
      }

  private def resolveReport(ref: ResourceRef, projectRef: ProjectRef, resolver: Resolver)(implicit
      caller: Caller
  ): UIO[ResolverResolutionResult[R]] =
    resolver match {
      case i: InProjectResolver    => inProjectResolve(ref, projectRef, i)
      case c: CrossProjectResolver => crossProjectResolve(ref, c)
    }

  private def inProjectResolve(
      ref: ResourceRef,
      projectRef: ProjectRef,
      resolver: InProjectResolver
  ): UIO[ResolverResolutionResult[R]] =
    fetchResource(ref, projectRef).redeem(
      e => ResolverReport.failed(resolver.id, projectRef -> e) -> None,
      f => ResolverReport.success(resolver.id) -> Some(f)
    )

  private def crossProjectResolve(ref: ResourceRef, resolver: CrossProjectResolver)(implicit
      caller: Caller
  ): UIO[ResolverResolutionResult[R]] = {
    import resolver.value._
    val fetchAclsMemoized = fetchAllAcls.memoizeOnSuccess

    def validateIdentities(acls: AclCollection, p: ProjectRef): IO[ProjectAccessDenied, Unit] = {
      def aclExists(identitySet: Set[Identity]): Boolean =
        acls.exists(identitySet, readPermission, AclAddress.Project(p))

      identityResolution match {
        case UseCurrentCaller if aclExists(caller.identities)        => IO.unit
        case ProvidedIdentities(identities) if aclExists(identities) => IO.unit
        case _                                                       => IO.raiseError(ProjectAccessDenied(p, identityResolution))
      }
    }

    def validateResourceTypes(r: ResourceF[R], p: ProjectRef): IO[ResourceTypesDenied, Unit] = {
      if (resourceTypes.isEmpty || resourceTypes.exists(r.types.contains))
        IO.unit
      else
        IO.raiseError(ResourceTypesDenied(p, r.types))
    }

    val initial: ResolverResolutionResult[R] = ResolverFailedReport(resolver.id, VectorMap.empty) -> None
    fetchAclsMemoized.flatMap { aclsCol =>
      projects.foldLeftM(initial) { (previous, projectRef) =>
        previous match {
          // We were able to resolve with this resolver, we keep that result
          case (s: ResolverSuccessReport, r) => IO.pure(s -> r)
          // No resolution was successful yet, we carry on
          case (f: ResolverFailedReport, _)  =>
            val resolve = for {
              _        <- validateIdentities(aclsCol, projectRef)
              resource <- fetchResource(ref, projectRef)
              _        <- validateResourceTypes(resource, projectRef)
            } yield ResolverSuccessReport(resolver.id, f.rejections) -> Option(resource)
            resolve.onErrorHandle { e =>
              f.copy(rejections = f.rejections + (projectRef -> e)) -> None
            }
        }
      }
    }
  }
}

object ResourceResolution {

  type FetchResource[R] = IO[ResolutionFetchRejection, ResourceF[R]]

  type ResolverResolutionResult[R] = (ResolverReport, Option[ResourceF[R]])

  private val resolverSearchParams = ResolverSearchParams(deprecated = Some(false), filter = _ => true)

  /**
    * Resolution for a given type of a resource based on resolvers
    * @param acls            an acls instance
    * @param resolvers       a resolvers instance
    * @param fetchResource   how to fetch the resource
    * @param readPermission  the mandatory permission
    */
  def apply[R](
      acls: Acls,
      resolvers: Resolvers,
      fetchResource: (ResourceRef, ProjectRef) => FetchResource[R],
      readPermission: Permission
  ) = new ResourceResolution(
    fetchAllAcls = acls.list(AnyOrganizationAnyProject(withAncestors = true)),
    listResolvers = (projectRef: ProjectRef) =>
      resolvers
        .list(projectRef, Pagination.OnePage, resolverSearchParams, ResourceF.defaultSort[Resolver])
        .map { r => r.results.map { r: ResultEntry[ResolverResource] => r.source.value }.toList },
    fetchResolver = (id: Iri, projectRef: ProjectRef) => resolvers.fetchActiveResolver(id, projectRef),
    fetchResource = fetchResource,
    readPermission = readPermission
  )

  /**
    * Resolution for a data resource based on resolvers
    * @param acls        an acls instance
    * @param resolvers   a resolvers instance
    * @param resources   a resources instance
    */
  def dataResource(acls: Acls, resolvers: Resolvers, resources: Resources): ResourceResolution[Resource] =
    ResourceResolution(acls, resolvers, resources.fetch[ResolutionFetchRejection], Permissions.resources.read)

  /**
    * Resolution for a schema resource based on resolvers
    * @param acls       an acls instance
    * @param resolvers  a resolvers instance
    * @param schemas    a schemas instance
    */
  def schemaResource(acls: Acls, resolvers: Resolvers, schemas: Schemas): ResourceResolution[Schema] =
    ResourceResolution(acls, resolvers, schemas.fetch[ResolutionFetchRejection], Permissions.schemas.read)

}
