package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError
import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalAclAddressFormatError
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import io.circe.{Encoder, Json}

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

  final private[sdk] val orgAddressRegex  = s"^/(${Label.regex.regex})$$".r
  final private[sdk] val projAddressRegex = s"^/(${Label.regex.regex})/(${Label.regex.regex})$$".r

  /**
    * Attempts to construct an AclAddress from the provided string. The accepted formats are the ones generated from
    * the [[AclAddress.string]] functions. The validation make use of the [[Label.regex]] to ensure compatibility with
    * a valid [[Label]].
    *
    * @param string the string representation of the AclAddress
    */
  final def fromString(string: String): Either[FormatError, AclAddress] = string match {
    case Root.string                 => Right(Root)
    case orgAddressRegex(org)        => Right(Organization(Label.unsafe(org))) // safe because the Label is already validated
    case projAddressRegex(org, proj) => Right(Project(Label.unsafe(org), Label.unsafe(proj)))
    case _                           => Left(IllegalAclAddressFormatError())
  }

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

  object Project {

    /**
      * Create project level address from [[ProjectRef]].
      */
    def apply(projectRef: ProjectRef): Project = Project(projectRef.organization, projectRef.project)
  }

  implicit val aclAddressOrdering: Ordering[AclAddress] = Ordering.by(_.string)

  implicit val aclAddressEncoder: Encoder[AclAddress] = Encoder.instance(a => Json.fromString(a.string))
}
