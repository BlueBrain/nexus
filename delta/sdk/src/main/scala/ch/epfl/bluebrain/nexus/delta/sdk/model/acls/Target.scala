package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Label

/**
  * Enumeration of possible ACL targets.
  */
sealed trait Target extends Product with Serializable {

  /**
    * the string representation of the target location
    */
  def string: String

  /**
    * @return the parent [[Target]] location for the current target, or None when there is no parent
    */
  def parent: Option[Target]

  override def toString: String = string
}

object Target {

  /**
    * An ACL target location where a certain ACL is anchored.
    */
  sealed trait TargetLocation extends Target {

    /**
      * Checks whether the current [[Target]] applies on the passed [[Target]].
      * E.g.: given the current target location is ''/'', only the passed target ''/'' will return true.
      * E.g.: given the current target location is ''/myorg'' the targets ''/org'', ''/{any}''will return true.
      */
    def appliesOn(that: Target): Boolean

  }

  /**
    * An ACL location that is a filter for several TargetLocation. E.g.: ''/\*'' filters every [[Organization]] target
    */
  sealed trait TargetFilterLocation extends Target

  type Root = Root.type

  /**
    * The top level target location.
    */
  final case object Root extends TargetLocation {

    val string: String = "/"

    def appliesOn(that: Target): Boolean = that == Root

    val parent: Option[Target] = None
  }

  /**
    * The organization level target location.
    */
  final case class Organization(org: Label) extends TargetLocation {

    val string = s"/$org"

    def appliesOn(that: Target): Boolean =
      that match {
        case AnyOrganization       => true
        case Organization(thatOrg) => org == thatOrg
        case _                     => false
      }

    val parent: Option[Target] = Some(Root)

  }

  /**
    * The project level target location.
    */
  final case class Project(org: Label, project: Label) extends TargetLocation {

    val string = s"/$org/$project"

    def appliesOn(that: Target): Boolean =
      that match {
        case AnyOrganizationAnyProject     => true
        case AnyProject(thatOrg)           => thatOrg == org
        case Project(thatOrg, thatProject) => thatOrg == org && thatProject == project
        case _                             => false
      }

    val parent: Option[Target] = Some(Organization(org))

  }

  type AnyOrganization           = AnyOrganization.type
  type AnyOrganizationAnyProject = AnyOrganizationAnyProject.type

  /**
    * A non-specific target location that targets any organization.
    */
  final case object AnyOrganization extends TargetFilterLocation {
    val string                 = s"/*"
    val parent: Option[Target] = Some(Root)
  }

  /**
    * A non-specific target location that targets any project.
    */
  final case class AnyProject(org: Label) extends TargetFilterLocation {
    val string                 = s"$org/*"
    val parent: Option[Target] = Some(Organization(org))
  }

  /**
    * A non-specific target location that targets any project.
    */
  final case object AnyOrganizationAnyProject extends TargetFilterLocation {
    val string                 = s"*/*"
    val parent: Option[Target] = Some(AnyOrganization)
  }

  implicit val orderingTarget: Ordering[TargetLocation] = Ordering.by(_.string)
}
