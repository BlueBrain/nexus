package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.RevisionNotFound
import monix.bio.{IO, Task}

/**
  * Operations pertaining to managing Access Control Lists.
  */
trait Acls {

  /**
    * Fetches the ACL resource for a ''target'' on the current revision.
    *
    * @param target the target location for the ACL
    */
  def fetch(target: Target): Task[Option[AclResource]]

  /**
    * Fetches the ACL resource for a ''target'' on the passed revision.
    *
    * @param target the target location for the ACL
    * @param rev    the revision to fetch
    */
  def fetchAt(target: Target, rev: Long): IO[RevisionNotFound, Option[AclResource]]

  /**
    * Fetches the ACL resource for a ''target'' on the current revision.
    *
    * @param target the target location for the ACL
    * @param self   flag to decide whether or not ACL of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''target'' or it's parents
    */
  final def fetch(target: Target, self: Boolean)(implicit caller: Caller): Task[Option[AclResource]] =
    if (self) fetch(target).map(filterSelf) else fetch(target)

  /**
    * Fetches the ACL resource for a ''target'' on the passed revision.
    *
    * @param target the target location for the ACL
    * @param self   flag to decide whether or not ACL of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''target'' or it's parents
    * @param rev    the revision to fetch
    */
  final def fetchAt(target: Target, self: Boolean, rev: Long)(implicit
      caller: Caller
  ): IO[RevisionNotFound, Option[AclResource]] =
    if (self) fetchAt(target, rev).map(filterSelf) else fetchAt(target, rev)

  /**
    * Fetches the ACL for a ''target''. If ACL does not exist, return an empty [[Acl]]
    *
    * @param target the target location for the ACL
    * @param self   flag to decide whether or not ACL of other identities than the provided ones should be included in the response.
    *               This is constrained by the current caller having ''acls/read'' permissions on the provided ''target'' or it's parents
    */
  final def fetchAcl(target: Target, self: Boolean)(implicit caller: Caller): Task[Acl] =
    fetch(target, self).map(_.fold(Acl.empty)(_.value))

  /**
    * Fetches the [[AclTargets]] of the provided ''target'' location with some filtering options.
    *
    * @param target    the target location where the ACLs are going to be looked up
    * @param ancestors flag to decide whether or not ancestor target locations should be included in the response
    * @param self      flag to decide whether or not ancestor from other identities than the provided ones should be included in the response
    * @param caller    the caller that contains the provided identities
    */
  def list(target: Target, ancestors: Boolean, self: Boolean)(implicit caller: Caller): Task[AclTargets]

  /**
    * Overrides ''acl'' on a ''target''.
    *
    * @param target the target location for the ACL
    * @param acl    the identity to permissions mapping to replace
    * @param rev    the last known revision of the resource
    */
  def replace(target: Target, acl: Acl, rev: Long): IO[AclRejection, AclResource]

  /**
    * Appends ''acl'' on a ''target''.
    *
    * @param target the target location for the ACL
    * @param acl    the identity to permissions mapping to append
    * @param rev    the last known revision of the resource
    */
  def append(target: Target, acl: Acl, rev: Long): IO[AclRejection, AclResource]

  /**
    * Subtracts ''acl'' on a ''target''.
    *
    * @param target the target location for the ACL
    * @param acl    the identity to permissions mapping to subtract
    * @param rev    the last known revision of the resource
    */
  def subtract(target: Target, acl: Acl, rev: Long): IO[AclRejection, AclResource]

  /**
    * Delete all ''acl'' on a ''target''.
    *
    * @param target the target location for the ACL
    * @param rev    the last known revision of the resource
    */
  def delete(target: Target, rev: Long): IO[AclRejection, AclResource]

  private def filterSelf(resourceOpt: Option[AclResource])(implicit caller: Caller): Option[AclResource] =
    resourceOpt.map(res => res.map(_.filter(caller.identities)))

}
