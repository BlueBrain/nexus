package ch.epfl.bluebrain.nexus.delta.sdk

import java.time.Instant

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.{Current, Initial}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.utils.IOUtils.instant
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * Operations pertaining to managing Access Control Lists.
  */
trait Acls {

  /**
    * Fetches the ACL resource for an ''address'' on the current revision.
    *
    * @param address the ACL address
    */
  def fetch(address: AclAddress): UIO[Option[AclResource]]

  /**
    * Fetches the ACL resource for an ''address'' and its ancestors on the current revision.
    *
    * @param address the ACL address
    */
  def fetchWithAncestors(address: AclAddress): UIO[AclCollection] =
    fetch(address).map(_.fold(AclCollection.empty)(res => AclCollection(res))).flatMap { cur =>
      address.parent match {
        case Some(parent) => fetchWithAncestors(parent).map(_ ++ cur)
        case None         => UIO.pure(cur)
      }
    }

  /**
    * Fetches the ACL resource with the passed address ''address'' on the passed revision.
    *
    * @param address the ACL address
    * @param rev    the revision to fetch
    */
  def fetchAt(address: AclAddress, rev: Long): IO[RevisionNotFound, Option[AclResource]]

  /**
    * Fetches the ACL resource with the passed address ''address'' and its ancestors on the passed revision.
    *
    * @param address the ACL address
    * @param rev    the revision to fetch
    */
  def fetchAtWithAncestors(address: AclAddress, rev: Long): IO[RevisionNotFound, AclCollection] =
    fetchAt(address, rev).map(_.fold(AclCollection.empty)(res => AclCollection(res))).flatMap { cur =>
      address.parent match {
        case Some(parent) => fetchAtWithAncestors(parent, rev).map(_ ++ cur)
        case None         => UIO.pure(cur)
      }
    }

  /**
    * Fetches the ACL resource with the passed ''address'' on the current revision.
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address the ACL address
    */
  final def fetchSelf(address: AclAddress)(implicit caller: Caller): UIO[Option[AclResource]] =
    fetch(address).map(filterSelf)

  /**
    * Fetches the ACL resource with the passed ''address'' and its ancestors on the current revision.
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address the ACL address
    */
  def fetchSelfWithAncestors(address: AclAddress)(implicit caller: Caller): UIO[AclCollection] =
    fetchWithAncestors(address).map(_.filter(caller.identities))

  /**
    * Fetches the ACL resource with the passed ''address'' on the passed revision.
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address the ACL address
    * @param rev    the revision to fetch
    */
  final def fetchSelfAt(address: AclAddress, rev: Long)(implicit
      caller: Caller
  ): IO[RevisionNotFound, Option[AclResource]] =
    fetchAt(address, rev).map(filterSelf)

  /**
    * Fetches the ACL resource with the passed ''address'' and ancestors on the passed revision.
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address the ACL address
    * @param rev    the revision to fetch
    */
  def fetchSelfAtWithAncestors(address: AclAddress, rev: Long)(implicit
      caller: Caller
  ): IO[RevisionNotFound, AclCollection] =
    fetchAtWithAncestors(address, rev).map(_.filter(caller.identities))

  /**
    * Fetches the ACL with the passed ''address''. If ACL does not exist, return an empty [[Acl]]
    *
    * @param address the ACL address
    */
  final def fetchAcl(address: AclAddress): UIO[Acl] =
    fetch(address).map(_.fold(Acl.empty)(_.value))

  /**
    * Fetches the ACL with the passed ''address''. If ACL does not exist, return an empty [[Acl]]
    * The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address the ACL address
    */
  final def fetchSelfAcl(address: AclAddress)(implicit caller: Caller): UIO[Acl] =
    fetchSelf(address).map(_.fold(Acl.empty)(_.value))

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address.
    *
    * @param filter    the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    */
  def list(filter: AclAddressFilter): UIO[AclCollection]

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address with identities present in the ''caller''.
    *
    * @param filter    the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    * @param caller    the caller that contains the provided identities
    */
  def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection]

  /**
    * A non terminating stream of events for ACLs. After emitting all known events it sleeps until new events
    * are recorded.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = NoOffset): Stream[Task, Envelope[AclEvent]]

  /**
    * The current ACLs events. The stream stops after emitting all known events.
    *
    * @param offset the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = NoOffset): Stream[Task, Envelope[AclEvent]]

  /**
    * Overrides ''acl'' on a the passed ''address''.
    *
    * @param address the ACL address
    * @param acl    the identity to permissions mapping to replace
    * @param rev    the last known revision of the resource
    */
  def replace(address: AclAddress, acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Appends ''acl'' on the passed ''address''.
    *
    * @param address the ACL address
    * @param acl    the identity to permissions mapping to append
    * @param rev    the last known revision of the resource
    */
  def append(address: AclAddress, acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Subtracts ''acl'' on the passed ''address''.
    *
    * @param address the ACL address
    * @param acl    the identity to permissions mapping to subtract
    * @param rev    the last known revision of the resource
    */
  def subtract(address: AclAddress, acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Delete all ''acl'' on the passed ''address''.
    *
    * @param address the ACL address
    * @param rev    the last known revision of the resource
    */
  def delete(address: AclAddress, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource]

  private def filterSelf(resourceOpt: Option[AclResource])(implicit caller: Caller): Option[AclResource] =
    resourceOpt.map(res => res.map(_.filter(caller.identities)))

}

object Acls {

  /**
    * The acl module type
    */
  final val moduleType: String = "acl"

  private[delta] def next(state: AclState, event: AclEvent): AclState = {
    def replaced(e: AclReplaced): AclState     =
      state match {
        case Initial    => Current(e.address, e.acl, 1L, e.instant, e.subject, e.instant, e.subject)
        case c: Current => c.copy(acl = e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }
    def appended(e: AclAppended): AclState     =
      state match {
        case Initial    => Current(e.address, e.acl, 1L, e.instant, e.subject, e.instant, e.subject)
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
  )(state: AclState, cmd: AclCommand)(implicit clock: Clock[UIO] = IO.clock): IO[AclRejection, AclEvent] = {

    def acceptChecking(acl: Acl)(f: Instant => AclEvent) =
      perms.flatMap(_.fetchPermissionSet).flatMap {
        case permissions if acl.permissions.subsetOf(permissions) => instant.map(f)
        case permissions                                          => IO.raiseError(UnknownPermissions(acl.permissions -- permissions))
      }

    def replace(c: ReplaceAcl)   =
      state match {
        case Initial if c.rev != 0                                        =>
          IO.raiseError(IncorrectRev(c.address, c.rev, 0L))
        case Initial if c.acl.hasEmptyPermissions                         =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.address))
        case Initial                                                      =>
          acceptChecking(c.acl)(AclReplaced(c.address, c.acl, 1L, _, c.subject))
        case s: Current if !s.acl.isEmpty && c.rev != s.rev               =>
          IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case s: Current if s.acl.isEmpty && c.rev != s.rev && c.rev != 0L =>
          IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions                      =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.address))
        case s: Current                                                   =>
          acceptChecking(c.acl)(AclReplaced(c.address, c.acl, s.rev + 1, _, c.subject))
      }
    def append(c: AppendAcl)     =
      state match {
        case Initial if c.rev != 0L                                                  =>
          IO.raiseError(IncorrectRev(c.address, c.rev, 0L))
        case Initial if c.acl.hasEmptyPermissions                                    =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.address))
        case Initial                                                                 =>
          acceptChecking(c.acl)(AclAppended(c.address, c.acl, c.rev + 1, _, c.subject))
        case s: Current if s.acl.permissions.nonEmpty && c.rev != s.rev              =>
          IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case s: Current if s.acl.permissions.isEmpty && c.rev != s.rev & c.rev != 0L =>
          IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions                                 =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.address))
        case s: Current if s.acl ++ c.acl == s.acl                                   =>
          IO.raiseError(NothingToBeUpdated(c.address))
        case s: Current                                                              =>
          acceptChecking(c.acl)(AclAppended(c.address, c.acl, s.rev + 1, _, c.subject))
      }
    def subtract(c: SubtractAcl) =
      state match {
        case Initial                                 =>
          IO.raiseError(AclNotFound(c.address))
        case s: Current if c.rev != s.rev            =>
          IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case _: Current if c.acl.hasEmptyPermissions =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.address))
        case s: Current if s.acl -- c.acl == s.acl   =>
          IO.raiseError(NothingToBeUpdated(c.address))
        case _: Current                              =>
          acceptChecking(c.acl)(AclSubtracted(c.address, c.acl, c.rev + 1, _, c.subject))
      }
    def delete(c: DeleteAcl)     =
      state match {
        case Initial                          => IO.raiseError(AclNotFound(c.address))
        case s: Current if c.rev != s.rev     => IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case s: Current if s.acl == Acl.empty => IO.raiseError(AclIsEmpty(c.address))
        case _: Current                       => instant.map(AclDeleted(c.address, c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplaceAcl  => replace(c)
      case c: AppendAcl   => append(c)
      case c: SubtractAcl => subtract(c)
      case c: DeleteAcl   => delete(c)
    }
  }
}
