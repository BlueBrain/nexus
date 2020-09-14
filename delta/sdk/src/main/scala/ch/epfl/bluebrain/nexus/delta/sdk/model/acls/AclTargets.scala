package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

import scala.collection.immutable.SortedMap

/**
  * A collection of ACL anchored into [[Target]] locations codified as a ordered Map (by key)
  * where the keys are [[Target]] locations and the values are [[AclResource]].
  * It specifies which permissions are applied for which identities in which locations.
  */
final case class AclTargets(value: SortedMap[Target, AclResource]) { self =>

  /**
    * Adds the provided ''acls'' to the current ''value'' and returns a new [[AclTargets]] with the added ACLs.
    *
    * @param acls the acls to be added
    */
  def ++(acls: AclTargets): AclTargets =
    AclTargets(acls.value.foldLeft(value) {
      case (acc, (target, aclToAdd)) =>
        acc.updatedWith(target)(acl => Some(acl.fold(aclToAdd)(_.map(_ ++ aclToAdd.value))))
    })

  /**
    * Adds a key pair of [[Target]] and [[AclResource]] to the current ''value'' and
    * returns a new [[AclTargets]] with the added acl.
    *
    * @param entry the key pair of [[Target]] and [[AclResource]] to be added
    */
  def +(entry: (Target, AclResource)): AclTargets =
    self ++ AclTargets(SortedMap(entry))

  /**
    * Generates a new [[AclTargets]] only containing the provided ''identities''.
    *
    * @param identities the identities to be filtered
    */
  def filter(identities: Set[Identity]): AclTargets =
    value.foldLeft(AclTargets.empty) {
      case (acc, (target, aclResource)) => acc + (target -> aclResource.map(_.filter(identities)))
    }

  /**
    * @return a new [[AclTargets]] containing the ACLs with non empty [[Acl]]
    */
  def removeEmpty(): AclTargets =
    AclTargets(value.foldLeft(SortedMap.empty[Target, AclResource]) {
      case (acc, (_, acl)) if acl.value.isEmpty => acc
      case (acc, (target, acl))                 =>
        val filteredAcl = acl.value.removeEmpty()
        if (filteredAcl.isEmpty) acc else acc + (target -> acl.as(filteredAcl))

    })

  /**
    * Checks if there are some ACL which contain any of the passed ''identities'' and ''permission'' in
    * the passed ''target'' location or its ancestors.
    *
    * @param identities  the list of identities to filter from the ''acls''
    * @param permission  the permission to filter
    * @param target      the target location where the passed ''identities'' and ''permissions'' are going to be checked
    * @return true if the conditions are met, false otherwise
    */
  def exists(identities: Set[Identity], permission: Permission, target: Target): Boolean =
    filter(identities).value.exists {
      case (aclTarget, aclResource) =>
        aclTarget.appliesWithAncestorsOn(target) && aclResource.value.permissions.contains(permission)
    }
}

object AclTargets {

  /**
    * An empty [[AclTargets]].
    */
  val empty: AclTargets = AclTargets(SortedMap.empty[Target, AclResource])

  /**
    * Convenience factory method to build a [[AclTargets]] from var args of [[Target]] to [[AclResource]] tuples.
    */
  final def apply(tuple: (Target, AclResource)*): AclTargets =
    AclTargets(SortedMap(tuple: _*))

}
