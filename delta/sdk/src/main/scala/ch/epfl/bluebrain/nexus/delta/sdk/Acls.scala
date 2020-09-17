package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
import monix.bio.{IO, Task, UIO}

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
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param target the target location for the ACL
    */
  final def fetchSelf(target: Target)(implicit caller: Caller): Task[Option[AclResource]] =
    fetch(target).map(filterSelf)

  /**
    * Fetches the ACL resource for a ''target'' on the passed revision.
    * The response only contains ACL with identities present in the provided ''caller''.
    *
   * @param target the target location for the ACL
    * @param rev    the revision to fetch
    */
  final def fetchSelfAt(target: Target, rev: Long)(implicit caller: Caller): IO[RevisionNotFound, Option[AclResource]] =
    fetchAt(target, rev).map(filterSelf)

  /**
    * Fetches the ACL for a ''target''. If ACL does not exist, return an empty [[Acl]]
    *
    * @param target the target location for the ACL
    */
  final def fetchAcl(target: Target): Task[Acl] =
    fetch(target).map(_.fold(Acl.empty)(_.value))

  /**
    * Fetches the ACL for a ''target''. If ACL does not exist, return an empty [[Acl]]
    * The response only contains ACL with identities present in the provided ''caller''.
    *
   * @param target the target location for the ACL
    */
  final def fetchSelfAcl(target: Target)(implicit caller: Caller): Task[Acl] =
    fetchSelf(target).map(_.fold(Acl.empty)(_.value))

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

object Acls {
  private[delta] def next(state: AclState, event: AclEvent): AclState = {
    def replaced(e: AclReplaced): AclState     =
      state match {
        case Initial    => Current(e.target, e.acl, 1L, e.instant, e.subject, e.instant, e.subject)
        case c: Current => c.copy(acl = e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }
    def appended(e: AclAppended): AclState     =
      state match {
        case Initial    => Current(e.target, e.acl, 1L, e.instant, e.subject, e.instant, e.subject)
        case c: Current => c.copy(acl = c.acl ++ e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }
    def subtracted(e: AclSubtracted): AclState =
      state match {
        case Initial    => Initial
        case c: Current => c.copy(acl = c.acl -- e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }
    def deleted(e: AclDeleted): AclState       =
      state match {
        case Initial    => Initial
        case c: Current => c.copy(acl = Acl.empty, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }
    event match {
      case ev: AclReplaced   => replaced(ev)
      case ev: AclAppended   => appended(ev)
      case ev: AclSubtracted => subtracted(ev)
      case ev: AclDeleted    => deleted(ev)
    }
  }

  private[delta] def evaluate(
      perms: UIO[Permissions]
  )(state: AclState, cmd: AclCommand)(implicit clock: Clock[UIO[*]] = IO.clock): IO[AclRejection, AclEvent] = {

    def acceptChecking(acl: Acl)(f: Instant => AclEvent) =
      perms.flatMap(_.fetchPermissionSet).flatMap {
        case permissions if acl.permissions.subsetOf(permissions) => instant.map(f)
        case permissions                                          => IO.raiseError(UnknownPermissions(acl.permissions -- permissions))
      }

    def replace(c: ReplaceAcl)   =
      state match {
        case Initial if c.rev != 0                                        =>
          IO.raiseError(IncorrectRev(c.target, c.rev, 0L))
        case Initial if c.acl.hasEmptyPermissions                         =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.target))
        case Initial                                                      =>
          acceptChecking(c.acl)(AclReplaced(c.target, c.acl, 1L, _, c.subject))
        case s: Current if !s.acl.isEmpty && c.rev != s.rev               =>
          IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case s: Current if s.acl.isEmpty && c.rev != s.rev && c.rev != 0L =>
          IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions                      =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.target))
        case s: Current                                                   =>
          acceptChecking(c.acl)(AclReplaced(c.target, c.acl, s.rev + 1, _, c.subject))
      }
    def append(c: AppendAcl)     =
      state match {
        case Initial if c.rev != 0L                                                  =>
          IO.raiseError(IncorrectRev(c.target, c.rev, 0L))
        case Initial if c.acl.hasEmptyPermissions                                    =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.target))
        case Initial                                                                 =>
          acceptChecking(c.acl)(AclAppended(c.target, c.acl, c.rev + 1, _, c.subject))
        case s: Current if s.acl.permissions.nonEmpty && c.rev != s.rev              =>
          IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case s: Current if s.acl.permissions.isEmpty && c.rev != s.rev & c.rev != 0L =>
          IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions                                 =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.target))
        case s: Current if s.acl ++ c.acl == s.acl                                   =>
          IO.raiseError(NothingToBeUpdated(c.target))
        case s: Current                                                              =>
          acceptChecking(c.acl)(AclAppended(c.target, c.acl, s.rev + 1, _, c.subject))
      }
    def subtract(c: SubtractAcl) =
      state match {
        case Initial                                 =>
          IO.raiseError(AclNotFound(c.target))
        case s: Current if c.rev != s.rev            =>
          IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.target))
        case s: Current if s.acl -- c.acl == s.acl   =>
          IO.raiseError(NothingToBeUpdated(c.target))
        case _: Current                              =>
          acceptChecking(c.acl)(AclSubtracted(c.target, c.acl, c.rev + 1, _, c.subject))
      }
    def delete(c: DeleteAcl)     =
      state match {
        case Initial                          => IO.raiseError(AclNotFound(c.target))
        case s: Current if c.rev != s.rev     => IO.raiseError(IncorrectRev(c.target, c.rev, s.rev))
        case s: Current if s.acl == Acl.empty => IO.raiseError(AclIsEmpty(c.target))
        case _: Current                       => instant.map(AclDeleted(c.target, c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplaceAcl  => replace(c)
      case c: AppendAcl   => append(c)
      case c: SubtractAcl => subtract(c)
      case c: DeleteAcl   => delete(c)
    }
  }
}
