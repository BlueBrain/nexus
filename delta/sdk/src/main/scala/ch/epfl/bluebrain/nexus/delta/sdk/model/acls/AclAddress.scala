package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of possible ACL addresses. An ACL address is the address where a certain ACL is anchored.
  */
sealed trait AclAddress extends Product with Serializable {

  /**
    * the string representation of the address
    */
  def string: String

  /**
    * @return the parent [[AclAddress]] for the current address, or None when there is no parent
    */
  def parent: Option[AclAddress]

  override def toString: String = string
}

object AclAddress {

  type Root = Root.type

  /**
    * The top level address.
    */
  final case object Root extends AclAddress {

    val string: String             = "/"
    val parent: Option[AclAddress] = None
  }

  /**
    * The organization level address.
    */
  final case class Organization(org: Label) extends AclAddress {

    val string                     = s"/$org"
    val parent: Option[AclAddress] = Some(Root)

  }

  /**
    * The project level address.
    */
  final case class Project(org: Label, project: Label) extends AclAddress {

    val string                     = s"/$org/$project"
    val parent: Option[AclAddress] = Some(Organization(org))

  }

  implicit val aclAddressOrdering: Ordering[AclAddress] = Ordering.by(_.string)
}
