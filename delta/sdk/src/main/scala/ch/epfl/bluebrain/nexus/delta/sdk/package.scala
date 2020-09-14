package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, Target}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import org.apache.jena.iri.IRI

package object sdk {

  /**
    * Type alias for a permission specific resource.
    */
  type PermissionsResource = ResourceF[IRI, Set[Permission]]

  /**
    * Type alias for a acl with its target location specific resource.
    */
  type AclTargetResource = ResourceF[Target, Acl]

  /**
    * Type alias for a acl specific resource.
    */
  type AclResource = ResourceF[IRI, Acl]

}
