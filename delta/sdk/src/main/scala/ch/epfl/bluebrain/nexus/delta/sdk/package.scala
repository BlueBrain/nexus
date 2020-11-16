package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Acl
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionSet
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.Project
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.resolvers.Resolver
import ch.epfl.bluebrain.nexus.delta.sdk.model.resources.Resource
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.Schema

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

}
