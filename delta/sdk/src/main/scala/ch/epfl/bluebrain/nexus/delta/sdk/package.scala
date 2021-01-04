package ch.epfl.bluebrain.nexus.delta

import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionSet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.{Resolver, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema
import monix.bio.IO

package object sdk {

  /**
    * Type alias for a permission specific resource.
    */
  type PermissionsResource = ResourceF[PermissionSet]

  /**
    * Type alias for a acl with its address specific resource.
    */
  type AclResource = ResourceF[Acl]

  /**
    * Type alias for a realm specific resource.
    */
  type RealmResource = ResourceF[Realm]

  /**
    * Type alias for an organization specific resource.
    */
  type OrganizationResource = ResourceF[Organization]

  /**
    * Type alias for a project specific resource.
    */
  type ProjectResource = ResourceF[Project]

  /**
    * Type alias for a data specific resource.
    */
  type DataResource = ResourceF[Resource]

  /**
    * Type alias for a schema specific resource.
    */
  type SchemaResource = ResourceF[Schema]

  /**
    * Type alias for a resolver specific resource.
    */
  type ResolverResource = ResourceF[Resolver]

  /**
    * Type alias for resolver resolution
    */
  type Resolve[A] = (ResourceRef, ProjectRef, Caller) => IO[ResourceResolutionReport, A]

  type AkkaSource = Source[ByteString, Any]

}
