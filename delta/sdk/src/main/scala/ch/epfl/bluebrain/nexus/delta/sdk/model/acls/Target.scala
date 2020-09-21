package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of possible ACL targets. A target is the location where a certain ACL is anchored.
  */
sealed trait Target extends Product with Serializable {

  /**
    * the string representation of the target location
    */
  def string: String

  /**
    * Checks whether the current [[Target]] applies on the passed [[Target]] including its ancestors.
    * E.g.: given the current target location is ''/'', any passed target will return true.
    * E.g.: given the current target location is ''/myorg'' only the target ''/org'' or targets ''/org/{any}'' will return true.
    */
  def appliesWithAncestorsOn(that: Target): Boolean

  /**
    * @return the parent [[Target]] location for the current target, or None when there is no parent
    */
  def parent: Option[Target]

  override def toString: String = string
}

object Target {

  type Root = Root.type

  /**
    * The top level target location.
    */
  final case object Root extends Target {

    val string: String = "/"

    def appliesWithAncestorsOn(that: Target): Boolean = true

    val parent: Option[Target] = None
  }

  /**
    * The organization level target location.
    */
  final case class Organization(org: Label) extends Target {

    val string = s"/$org"

    def appliesWithAncestorsOn(that: Target): Boolean =
      that match {
        case Root                  => false
        case Organization(thatOrg) => org == thatOrg
        case Project(thatOrg, _)   => org == thatOrg
      }

    val parent: Option[Target] = Some(Root)

  }

  /**
    * The project level target location.
    */
  final case class Project(org: Label, project: Label) extends Target {

    val string = s"/$org/$project"

    def appliesWithAncestorsOn(that: Target): Boolean = that == this

    val parent: Option[Target] = Some(Organization(org))

  }

  implicit val orderingTarget: Ordering[Target] = Ordering.by(_.string)
}
