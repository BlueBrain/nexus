package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

/**
  * An Access Control List codified as a map where the keys are [[Identity]] and the values are a set of [[Permission]].
  * It specifies which permissions are applied for which identities.
  */
final case class Acl(value: Map[Identity, Set[Permission]]) {

  /**
    * Adds the provided ''acl'' to the current ''value'' and returns a new [[Acl]] with the added ACL.
    *
    * @param acl the acl to be added
    */
  def ++(acl: Acl): Acl =
    Acl(acl.value.foldLeft(value) {
      case (acc, (id, permsToAdd)) => acc.updatedWith(id)(perms => Some(perms.fold(permsToAdd)(_ ++ permsToAdd)))
    })

  /**
    * removes the provided ''acl'' from the current ''value'' and returns a new [[Acl]] with the subtracted ACL.
    *
    * @param acl the acl to be subtracted
    */
  def --(acl: Acl): Acl =
    Acl(acl.value.foldLeft(value) {
      case (acc, (id, permsToDelete)) => acc.updatedWith(id)(_.map(_ -- permsToDelete).filter(_.nonEmpty))
    })

  /**
    * @return a collapsed Set of [[Permission]] from all the identities
    */
  def permissions: Set[Permission] =
    value.foldLeft(Set.empty[Permission]) { case (acc, (_, perms)) => acc ++ perms }

  /**
    * @return ''true'' if the underlying map is empty or if any permission set is empty
    */
  def hasEmptyPermissions: Boolean =
    value.isEmpty || value.exists { case (_, perms) => perms.isEmpty }

  /**
    * @return ''true'' if the underlying map is empty or if every permission set is empty
    */
  def isEmpty: Boolean             =
    value.isEmpty || value.forall { case (_, perms) => perms.isEmpty }

  /**
    * @return ''true'' if the underlying map is not empty and every permission set is not empty
    */
  def nonEmpty: Boolean            =
    !isEmpty

  /**
    * @return a new [[Acl]] without the identities that have empty permission sets
    */
  def removeEmpty(): Acl                     =
    Acl(value.filter { case (_, perms) => perms.nonEmpty })

  /**
    * Filters the passed identities from the current value map.
    */
  def filter(identities: Set[Identity]): Acl =
    Acl(value.view.filterKeys(identities.contains).toMap)

  /**
    * Determines if the current ACL contains the argument ''permission'' for at least one of the provided ''identities''.
    *
    * @param identities the identities to consider for having the permission
    * @param permission the permission to check
    * @return true if at least one of the provided identities has the provided permission
    */
  def hasPermission(identities: Set[Identity], permission: Permission): Boolean =
    value.exists {
      case (id, perms) => identities.contains(id) && perms.contains(permission)
    }
}

object Acl {

  /**
    * An empty [[Acl]].
    */
  val empty: Acl = Acl(Map.empty[Identity, Set[Permission]])

  /**
    * Convenience factory method to build an ACL from var args of ''Identity'' to ''Permissions'' tuples.
    */
  def apply(acl: (Identity, Set[Permission])*): Acl =
    Acl(acl.toMap)
}
