package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.Schemas
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Identity, ProjectRef, ResourceRef}
import monix.bio.{IO, UIO}

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
      checkAcls: (ProjectRef, Set[Identity]) => UIO[Boolean],
      listResolvers: ProjectRef => UIO[List[Resolver]],
      fetchResolver: (Iri, ProjectRef) => IO[ResolverRejection, Resolver],
      fetch: (ResourceRef, ProjectRef) => FetchResource[R]
  ): ResourceResolution[R] =
    new ResolverResolution(checkAcls, listResolvers, fetchResolver, fetch, (r: ResourceF[R]) => r.types)

  /**
    * Resolution for a given type of a resource based on resolvers
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param fetchResource
    *   how to fetch the resource
    * @param readPermission
    *   the mandatory permission
    */
  def apply[R](
      aclCheck: AclCheck,
      resolvers: Resolvers,
      fetchResource: (ResourceRef, ProjectRef) => FetchResource[R],
      readPermission: Permission
  ): ResourceResolution[R] = ResolverResolution(aclCheck, resolvers, fetchResource, _.types, readPermission)

  /**
    * Resolution for a data resource based on resolvers
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param resources
    *   a resources instance
    */
  def dataResource(aclCheck: AclCheck, resolvers: Resolvers, resources: Resources): ResourceResolution[Resource] =
    apply(
      aclCheck,
      resolvers,
      (ref: ResourceRef, project: ProjectRef) => resources.fetch(ref, project).redeem(_ => None, Some(_)),
      Permissions.resources.read
    )

  /**
    * Resolution for a schema resource based on resolvers
    * @param aclCheck
    *   how to check acls
    * @param resolvers
    *   a resolvers instance
    * @param schemas
    *   a schemas instance
    */
  def schemaResource(aclCheck: AclCheck, resolvers: Resolvers, schemas: Schemas): ResourceResolution[Schema] =
    apply(
      aclCheck,
      resolvers,
      (ref: ResourceRef, project: ProjectRef) => schemas.fetch(ref, project).redeem(_ => None, Some(_)),
      Permissions.schemas.read
    )

}
