package ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.ResolverResolution.{FetchResource, ResourceResolution}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
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
    * @param acls
    *   an acls instance
    * @param resolvers
    *   a resolvers instance
    * @param fetchResource
    *   how to fetch the resource
    * @param readPermission
    *   the mandatory permission
    */
  def apply[R](
      acls: Acls,
      resolvers: Resolvers,
      fetchResource: (ResourceRef, ProjectRef) => FetchResource[R],
      readPermission: Permission
  ): ResourceResolution[R] = ResolverResolution(acls, resolvers, fetchResource, _.types, readPermission)

  /**
    * Resolution for a data resource based on resolvers
    * @param acls
    *   an acls instance
    * @param resolvers
    *   a resolvers instance
    * @param resources
    *   a resources instance
    */
  def dataResource(acls: Acls, resolvers: Resolvers, resources: Resources): ResourceResolution[Resource] =
    apply(
      acls,
      resolvers,
      (ref: ResourceRef, project: ProjectRef) => resources.fetch(ref, project).redeem(_ => None, Some(_)),
      Permissions.resources.read
    )

  /**
    * Resolution for a schema resource based on resolvers
    * @param acls
    *   an acls instance
    * @param resolvers
    *   a resolvers instance
    * @param schemas
    *   a schemas instance
    */
  def schemaResource(acls: Acls, resolvers: Resolvers, schemas: Schemas): ResourceResolution[Schema] =
    apply(
      acls,
      resolvers,
      (ref: ResourceRef, project: ProjectRef) => schemas.fetch(ref, project).redeem(_ => None, Some(_)),
      Permissions.schemas.read
    )

}
