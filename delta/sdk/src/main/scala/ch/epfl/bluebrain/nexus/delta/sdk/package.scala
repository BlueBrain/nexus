package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.Organization
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import org.apache.jena.iri.IRI

package object sdk {

  /**
    * Type alias for a permission specific resource.
    */
  type PermissionsResource = ResourceF[IRI, Set[Permission]]

  /**
    * Type alias for a acl with its address specific resource.
    */
  type AclResource = ResourceF[AclAddress, Acl]

  /**
    * Type alias for a realm specific resource.
    */
  type RealmResource = ResourceF[Label, Realm]

  /**
    * Type alias for an organization specific resource.
    */
  type OrganizationResource = ResourceF[Label, Organization]

  /**
    * Type alias for a project specific resource.
    */
  type ProjectResource = ResourceF[ProjectRef, Project]

}
