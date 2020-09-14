package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

sealed abstract class AclRejection(val msg: String) extends Product with Serializable

object AclRejection {

  /**
    * Signals an attempt to append/subtract ACLs that won't change the current state.
    *
    * @param target the target location for the ACL
    */
  final case class NothingToBeUpdated(target: Target)
      extends AclRejection(s"The ACL on target location '$target' will not change after applying the provided update.")

  /**
    * Signals an attempt to modify ACLs that do not exists.
    *
    * @param target the target location for the ACL
    */
  final case class AclNotFound(target: Target)
      extends AclRejection(s"The ACL on target location '$target' does not exists.")

  /**
    * Signals an attempt to delete ACLs that are already empty.
    *
    * @param target the target location for the ACL
    */
  final case class AclIsEmpty(target: Target) extends AclRejection(s"The ACL on target location '$target' is empty.")

  /**
    * Signals an attempt to interact with an ACL collection with an incorrect revision.
    *
    * @param target the target location for the ACL
    * @param provided the provided revision
    * @param expected the expected revision
    */
  final case class IncorrectRev(target: Target, provided: Long, expected: Long)
      extends AclRejection(
        s"Incorrect revision '$provided' provided, expected '$expected', the ACL on target location '$target' may have been updated since last seen."
      )

  /**
    * Signals an attempt to create/replace/append/subtract ACL collection which contains void permissions.
    *
    * @param target the target location for the ACL
    */
  final case class AclCannotContainEmptyPermissionCollection(target: Target)
      extends AclRejection(s"The ACL for target location '$target' cannot contain an empty permission collection.")

  /**
    * Signals that an acl operation could not be performed because of unknown referenced permissions.
    *
    * @param permissions the unknown permissions
    */
  final case class UnknownPermissions(permissions: Set[Permission])
      extends AclRejection(
        s"Some of the permissions specified are not known: '${permissions.mkString("\"", ", ", "\"")}'"
      )
}
