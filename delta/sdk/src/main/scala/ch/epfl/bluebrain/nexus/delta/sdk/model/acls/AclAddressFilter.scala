package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of possible ACL address filtering. An ACL filter address is a filter which can match multiple [[AclAddress]].
  */
sealed trait AclAddressFilter extends Product with Serializable {

  /**
    * the string representation of the address
    */
  def string: String

  /**
    * Checks whether the passed ''address'' matches the current filter
    * @param address the ACL address
    */
  def matches(address: AclAddress): Boolean

  /**
    * Checks whether the passed ''address'' or its ancestors matches the current filter
    * @param address the ACL address
    */
  def matchesWithAncestors(address: AclAddress): Boolean

  override def toString: String = string

}

object AclAddressFilter {
  type AnyOrganization           = AnyOrganization.type
  type AnyOrganizationAnyProject = AnyOrganizationAnyProject.type

  /**
    * An ACL filtering that matches any [[AclAddress.Organization]] address
    */
  final case object AnyOrganization extends AclAddressFilter {
    val string = "/*"

    def matches(address: AclAddress): Boolean =
      address match {
        case AclAddress.Organization(_) => true
        case _                          => false
      }

    def matchesWithAncestors(address: AclAddress): Boolean =
      address match {
        case AclAddress.Root            => true
        case AclAddress.Organization(_) => true
        case _                          => false
      }
  }

  /**
    * An ACL filtering that matches any [[AclAddress.Project]] address within a fixed [[AclAddress.Organization]]
    */
  final case class AnyProject(org: Label) extends AclAddressFilter {
    val string = s"/$org/*"

    def matches(address: AclAddress): Boolean =
      address match {
        case AclAddress.Project(thatOrg, _) => org == thatOrg
        case _                              => false
      }

    def matchesWithAncestors(address: AclAddress): Boolean =
      address match {
        case AclAddress.Root                  => true
        case AclAddress.Organization(thatOrg) => org == thatOrg
        case AclAddress.Project(thatOrg, _)   => org == thatOrg
      }
  }

  /**
    * An ACL filtering that matches any [[AclAddress.Project]] and any [[AclAddress.Organization]]
    */
  final case object AnyOrganizationAnyProject extends AclAddressFilter {
    val string = "/*/*"

    def matches(address: AclAddress): Boolean =
      address match {
        case AclAddress.Project(_, _) => true
        case _                        => false
      }

    def matchesWithAncestors(address: AclAddress): Boolean = true
  }
}
