package ch.epfl.bluebrain.nexus.delta

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.PermissionSet
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.Project
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model.{Resolver, ResourceResolutionReport}
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.schemas.model.Schema
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}
import fs2.Stream

import java.nio.ByteBuffer

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
    * Type alias for a type hierarchy specific resource.
    */
  type TypeHierarchyResource = ResourceF[TypeHierarchy]

  /**
    * Type alias for resolver resolution
    */
  type Resolve[A] = (ResourceRef, ProjectRef, Caller) => IO[Either[ResourceResolutionReport, A]]

  /**
    * Type alias for file data
    */
  type FileData = Stream[IO, ByteBuffer]
}
