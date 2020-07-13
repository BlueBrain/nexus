package ch.epfl.bluebrain.nexus.iam.acls

import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.Path

/**
  * Enumeration of ACL collection command types.
  */
sealed trait AclCommand extends Product with Serializable {

  /**
    * @return the target path for the ACL
    */
  def path: Path

  /**
    * @return the last known revision of the resource when this command was created
    */
  def rev: Long

  /**
    * @return the identities which were used to created this command
    */
  def subject: Subject

}

object AclCommand {

  /**
    * An intent to replace ACL.
    *
    * @param path    the target path for the ACL
    * @param acl     the ACL to be replaced, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    * @return the identities which were used to created this command
    */
  final case class ReplaceAcl(
      path: Path,
      acl: AccessControlList,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to append ACL.
    *
    * @param path    the target path for the ACL
    * @param acl     the ACL to be appended, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class AppendAcl(
      path: Path,
      acl: AccessControlList,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to subtract ACL.
    *
    * @param path    the target path for the ACL
    * @param acl     the ACL to be subtracted, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class SubtractAcl(
      path: Path,
      acl: AccessControlList,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to delete ACL.
    *
    * @param path    the target path for the ACL
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class DeleteAcl(
      path: Path,
      rev: Long,
      subject: Subject
  ) extends AclCommand
}
