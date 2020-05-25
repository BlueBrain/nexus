package ch.epfl.bluebrain.nexus.acls

import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path./
import io.circe.{Decoder, Encoder}

/**
  * Enumeration of possible ACL targets
  */
sealed trait AclTarget extends Product with Serializable {

  /**
    * @return a path representation of the AclTarget location
    */
  def toPath: Path

  /**
    * The AclTarget location parent.
    */
  def parent: AclTarget

  /**
    * Evaluates if the current acl location is an ancestor or equals the passed acl ''target'' location.
    */
  def isAncestorOrEqualOf(target: AclTarget): Boolean

  /**
    * @return true when some of the AclTarget locations has ''*'', false otherwise
    */
  def hasAny: Boolean

  override def toString: String =
    toPath.toString()

}
object AclTarget {

  private val any = "*"

  /**
    * An ACL that applies to all the organizations and projects in the service
    */
  final case object RootAcl extends AclTarget {
    override val toPath: Path                                    = /
    override val parent: AclTarget                               = this
    override def isAncestorOrEqualOf(target: AclTarget): Boolean = true
    override val hasAny: Boolean                                 = false
  }

  /**
    * An ACL that applies to the passed organization with name ''orgLabel''
    */
  final case class OrgAcl(orgLabel: String) extends AclTarget {
    override val toPath: Path      = /(orgLabel)
    override def parent: AclTarget = RootAcl
    override def isAncestorOrEqualOf(target: AclTarget): Boolean =
      target match {
        case RootAcl                  => false
        case OrgAcl(targetOrg)        => targetOrg == orgLabel || targetOrg == any
        case ProjectAcl(targetOrg, _) => targetOrg == orgLabel || targetOrg == any
      }
    override def hasAny: Boolean = orgLabel == any
  }

  /**
    * An ACL that applies to the passed project with name ''orgLabel/projectLabel''
    */
  final case class ProjectAcl(orgLabel: String, projectLabel: String) extends AclTarget {
    override val toPath: Path      = /(orgLabel) / projectLabel
    override def parent: AclTarget = OrgAcl(orgLabel)
    override def isAncestorOrEqualOf(target: AclTarget): Boolean =
      target match {
        case RootAcl | OrgAcl(_)          => false
        case ProjectAcl(_, targetProject) => targetProject == projectLabel || targetProject == any
      }
    override def hasAny: Boolean = orgLabel == any || projectLabel == any
  }

  final def apply(str: String): Option[AclTarget] =
    if (str.contains("//")) None
    else
      str.split("/").filterNot(_.isBlank).toList match {
        case Nil                => Some(RootAcl)
        case org :: Nil         => Some(OrgAcl(org))
        case org :: proj :: Nil => Some(ProjectAcl(org, proj))
        case _                  => None
      }

  implicit final val aclTargetEncoder: Encoder[AclTarget] =
    Encoder.encodeString.contramap(_.toString)

  implicit final val aclTargetDecoder: Decoder[AclTarget] =
    Decoder.decodeString.emap(apply(_).toRight("Illegal acl target format"))
}
