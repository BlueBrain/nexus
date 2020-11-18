package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission

import scala.collection.immutable.SortedMap

/**
  * A collection of ACL anchored into their [[AclAddress]] codified as a ordered Map (by key)
  * where the keys are [[AclAddress]] and the values are [[AclResource]].
  * It specifies which permissions are applied for which identities in which address.
  */
final case class AclCollection private (value: SortedMap[AclAddress, AclResource]) { self =>

  /**
    * @return true if collection of ACLs is empty, false otherwise
    */
  def isEmpty: Boolean = value.isEmpty

  /**
    * Fetch the [[AclCollection]] for all [[AclAddress]] that match the passed ''filter''.
    *
    * @param filter the ACL address filter
    */
  def fetch(filter: AclAddressFilter): AclCollection =
    AclCollection(value.filter { case (address, _) => filter.matches(address) })

  /**
    * Adds the provided ''acls'' to the current ''value'' and returns a new [[AclCollection]] with the added ACLs.
    *
    * @param acls the acls to be added
    */
  def ++(acls: AclCollection): AclCollection         =
    AclCollection(acls.value.foldLeft(value) {
      case (acc, (address, aclToAdd)) if aclToAdd.value.address != address => acc // should not happen, ignore it
      case (acc, (address, aclToAdd))                                      =>
        acc.updatedWith(address)(acl => Some(acl.fold(aclToAdd)(c => aclToAdd.map(c.value ++ _))))
    })

  /**
    * Adds an [[AclResource]] to the current ''value'' and
    * returns a new [[AclCollection]] with the added acl.
    *
    * @param entry the [[AclResource]] to be added
    */
  def +(entry: AclResource): AclCollection =
    self ++ AclCollection(SortedMap(entry.value.address -> entry))

  /**
    * Removes an [[AclResource]] to the current ''value'' and
    * returns a new [[AclCollection]] with the subtracted acl.
    *
    * @param entry the [[AclResource]] to be subtracted
    */
  def -(entry: AclResource): AclCollection =
    AclCollection(self.value.updatedWith(entry.value.address)(_.fold[Option[AclResource]](None) { cur =>
      val updated = entry.map(acl => cur.value -- acl)
      Option.when(updated.value.nonEmpty)(updated)
    }))

  /**
    * Removes the [[AclResource]] for the passed ''address''.
    */
  def -(address: AclAddress): AclCollection =
    AclCollection(self.value - address)

  /**
    * Generates a new [[AclCollection]] only containing the provided ''identities''.
    *
    * @param identities the identities to be filtered
    */
  def filter(identities: Set[Identity]): AclCollection =
    value.foldLeft(AclCollection.empty) { case (acc, (_, aclResource)) =>
      acc + aclResource.map(_.filter(identities))
    }

  /**
    * Generates a new [[AclCollection]] only containing [[Acl]]s
    * where user has `permission` on the [[Acl]] or ancestor
    *
    * @param identities the identities of the user
    * @param permission the permission to filter by
    */
  def filterByPermission(identities: Set[Identity], permission: Permission): AclCollection =
    AclCollection(value.filter { case (address, _) =>
      exists(identities, permission, address)
    })

  /**
    * @return a new [[AclCollection]] containing the ACLs with non empty [[Acl]]
    */
  def removeEmpty(): AclCollection =
    AclCollection(value.foldLeft(SortedMap.empty[AclAddress, AclResource]) {
      case (acc, (_, acl)) if acl.value.isEmpty => acc
      case (acc, (address, acl))                =>
        val filteredAcl = acl.value.removeEmpty()
        if (filteredAcl.isEmpty) acc else acc + (address -> acl.as(filteredAcl))

    })

  /**
    * Checks if there are some ACL which contain any of the passed ''identities'' and ''permission'' in
    * the passed ''address'' or its ancestors.
    *
    * @param identities  the list of identities to filter from the ''acls''
    * @param permission  the permission to filter
    * @param address      the acl address where the passed ''identities'' and ''permissions'' are going to be checked
    * @return true if the conditions are met, false otherwise
    */
  def exists(identities: Set[Identity], permission: Permission, address: AclAddress): Boolean =
    filter(identities).value.exists { case (curAddress, aclResource) =>
      curAddress == address && aclResource.value.permissions.contains(permission)
    } || address.parent.fold(false)(exists(identities, permission, _))
}

object AclCollection {

  /**
    * An empty [[AclCollection]].
    */
  val empty: AclCollection = AclCollection(SortedMap.empty[AclAddress, AclResource])

  /**
    * Convenience factory method to build a [[AclCollection]] [[AclResource]]s.
    */
  final def apply(resources: AclResource*): AclCollection =
    AclCollection(SortedMap(resources.map(res => res.value.address -> res): _*))

}
