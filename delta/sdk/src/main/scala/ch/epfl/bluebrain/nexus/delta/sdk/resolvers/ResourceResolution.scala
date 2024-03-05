package ch.epfl.bluebrain.nexus.delta.sdk.resolvers

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.model.Fetch.FetchF
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.ResolverResolution.{DeprecationCheck, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.Resolver
import ch.epfl.bluebrain.nexus.delta.sdk.resources.FetchResource
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}

object ResourceResolution {

  /**
    * Resolution for a given type of a resource based on resolvers
    * @param checkAcls
    *   how to check acls
    * @param listResolvers
    *   how to list resolvers
    * @param fetchResolver
    *   how to fetch a resolver
    * @param fetch
    *   how to fetch the resource
    */
  def apply[R](
      checkAcls: (ProjectRef, Set[Identity]) => IO[Boolean],
      listResolvers: ProjectRef => IO[List[Resolver]],
      fetchResolver: (Iri, ProjectRef) => IO[Resolver],
      fetch: (ResourceRef, ProjectRef) => FetchF[R],
      excludeDeprecated: Boolean
  ): ResourceResolution[R] =
    new ResolverResolution(
      checkAcls,
      listResolvers,
      fetchResolver,
      fetch,
      (r: ResourceF[R]) => r.types,
      deprecationCheck(excludeDeprecated)
    )

  /**
    * Resolution for a given type of a resource based on resolvers
    *
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param fetchResource
    *   how to fetch the resource
    * @param readPermission
    *   the mandatory permission
    * @param excludeDeprecated
    *   to exclude deprecated resources from the resolution
    */
  def apply[R](
      aclCheck: AclCheck,
      resolvers: Resolvers,
      fetchResource: (ResourceRef, ProjectRef) => FetchF[R],
      readPermission: Permission,
      excludeDeprecated: Boolean
  ): ResourceResolution[R] =
    ResolverResolution(aclCheck, resolvers, fetchResource, _.types, readPermission, deprecationCheck(excludeDeprecated))

  /**
    * Resolution for a data resource based on resolvers
    *
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param xas
    *   the transactors
    * @param excludeDeprecated
    *   to exclude deprecated resources from the resolution
    */
  def dataResource(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      xas: Transactors,
      excludeDeprecated: Boolean
  ): ResourceResolution[Resource] =
    apply(
      aclCheck,
      resolvers,
      FetchResource(xas).latest(_, _),
      Permissions.resources.read,
      excludeDeprecated
    )

  /**
    * Resolution for a schema resource based on resolvers
    *
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param schemas
    *   a schemas instance
    * @param excludeDeprecated
    *   to exclude deprecated resources from the resolution
    */
  def schemaResource(
      aclCheck: AclCheck,
      resolvers: Resolvers,
      schemas: Schemas,
      excludeDeprecated: Boolean
  ): ResourceResolution[Schema] =
    apply(
      aclCheck,
      resolvers,
      (ref: ResourceRef, project: ProjectRef) => schemas.fetch(ref, project).redeem(_ => None, Some(_)),
      Permissions.schemas.read,
      excludeDeprecated
    )

  private def deprecationCheck[R](excludeDeprecated: Boolean) =
    DeprecationCheck[ResourceF[R]](excludeDeprecated, _.deprecated)

}
