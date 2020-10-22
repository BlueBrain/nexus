package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of possible ACL address filtering. An ACL filter address is a filter which can match multiple [[AclAddress]].
  */
sealed trait AclAddressFilter extends Product with Serializable {

  /**
    * Boolean signalling whether ancestor paths should be included.
    */
  def withAncestors: Boolean

  /**
    * the string representation of the address
    */
  def string: String

  /**
    * Checks whether the passed ''address'' matches the current filter
    * @param address the ACL address
    */
  def matches(address: AclAddress): Boolean

  override def toString: String = string

}

object AclAddressFilter {

  /**
    * An ACL filtering that matches any [[AclAddress.Organization]] address
    */
  final case class AnyOrganization(withAncestors: Boolean) extends AclAddressFilter {
    val string = "/*"

    def matches(address: AclAddress): Boolean =
      (address, withAncestors) match {
        case (AclAddress.Organization(_), _) => true
        case (AclAddress.Root, true)         => true
        case _                               => false
      }
  }

  /**
    * An ACL filtering that matches any [[AclAddress.Project]] address within a fixed [[AclAddress.Organization]]
    */
  final case class AnyProject(org: Label, withAncestors: Boolean) extends AclAddressFilter {
    val string = s"/$org/*"

    def matches(address: AclAddress): Boolean =
      (address, withAncestors) match {
        case (AclAddress.Project(thatOrg, _), _)      => org == thatOrg
        case (AclAddress.Organization(thatOrg), true) => org == thatOrg
        case (AclAddress.Root, true)                  => true
        case _                                        => false
      }
  }

  /**
    * An ACL filtering that matches any [[AclAddress.Project]] and any [[AclAddress.Organization]]
    */
  final case class AnyOrganizationAnyProject(withAncestors: Boolean) extends AclAddressFilter {
    val string = "/*/*"

    def matches(address: AclAddress): Boolean =
      (address, withAncestors) match {
        case (AclAddress.Project(_, _), _) => true
        case (_, true)                     => true
        case _                             => false
      }

  }
}
