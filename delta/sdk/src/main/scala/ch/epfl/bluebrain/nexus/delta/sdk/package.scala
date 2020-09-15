package ch.epfl.bluebrain.nexus.delta

import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, Target}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, ResourceF}
import org.apache.jena.iri.IRI

package object sdk {

  /**
    * Type alias for a permission specific resource.
    */
  type PermissionsResource = ResourceF[IRI, Set[Permission]]

  /**
    * Type alias for a acl with its target location specific resource.
    */
  type AclResource = ResourceF[Target, Acl]

  /**
    * Type alias for a realm specific resource.
    */
  type RealmResource = ResourceF[Label, Realm]

}
