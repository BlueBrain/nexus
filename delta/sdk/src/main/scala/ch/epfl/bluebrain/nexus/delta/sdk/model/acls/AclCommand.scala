package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject

/**
  * Enumeration of ACL collection command types.
  */
sealed trait AclCommand extends Product with Serializable {

  /**
    * @return the target location for the ACL
    */
  def target: Target

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
    * @param target  the target location for the ACL
    * @param acl     the ACL to be replaced, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    * @return the identities which were used to created this command
    */
  final case class ReplaceAcl(
      target: Target,
      acl: Acl,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to append ACL.
    *
    * @param target  the target location for the ACL
    * @param acl     the ACL to be appended, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class AppendAcl(
      target: Target,
      acl: Acl,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to subtract ACL.
    *
    * @param target  the target location for the ACL
    * @param acl     the ACL to be subtracted, represented as a mapping of identities to permissions
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class SubtractAcl(
      target: Target,
      acl: Acl,
      rev: Long,
      subject: Subject
  ) extends AclCommand

  /**
    * An intent to delete ACL.
    *
    * @param target  the target location for the ACL
    * @param rev     the last known revision of the resource when this command was created
    * @param subject the subject used to created this command
    */
  final case class DeleteAcl(
      target: Target,
      rev: Long,
      subject: Subject
  ) extends AclCommand
}
