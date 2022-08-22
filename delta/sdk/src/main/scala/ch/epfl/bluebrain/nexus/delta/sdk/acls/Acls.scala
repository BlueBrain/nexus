package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.kernel.utils.IOUtils.instant
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{IdentityRealm, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, EnvelopeStream, Label}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.{GlobalEntityDefinition, StateMachine}
import monix.bio.{IO, UIO}

import java.time.Instant

/**
  * Operations pertaining to managing Access Control Lists.
  */
trait Acls {

  /**
    * Fetches the ACL resource for an ''address'' on the current revision.
    *
    * @param address
    *   the ACL address
    */
  def fetch(address: AclAddress): IO[AclNotFound, AclResource]

  /**
    * Fetches the ACL resource for an ''address'' and its ancestors on the current revision.
    *
    * @param address
    *   the ACL address
    */
  def fetchWithAncestors(address: AclAddress): UIO[AclCollection] = {

    def recurseOnParentAddress(current: AclCollection) =
      address.parent match {
        case Some(parent) => fetchWithAncestors(parent).map(_ ++ current)
        case None         => UIO.pure(current)
      }

    fetch(address).attempt.flatMap {
      case Left(_)         => recurseOnParentAddress(AclCollection.empty)
      case Right(resource) => recurseOnParentAddress(AclCollection(resource))
    }
  }

  /**
    * Fetches the ACL resource with the passed address ''address'' on the passed revision.
    *
    * @param address
    *   the ACL address
    * @param rev
    *   the revision to fetch
    */
  def fetchAt(address: AclAddress, rev: Int): IO[AclRejection.NotFound, AclResource]

  /**
    * Fetches the ACL resource with the passed ''address'' on the current revision. The response only contains ACL with
    * identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    */
  final def fetchSelf(address: AclAddress)(implicit caller: Caller): IO[AclNotFound, AclResource] =
    fetch(address).map(filterSelf)

  /**
    * Fetches the ACL resource with the passed ''address'' and its ancestors on the current revision. The response only
    * contains ACL with identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    */
  def fetchSelfWithAncestors(address: AclAddress)(implicit caller: Caller): UIO[AclCollection] =
    fetchWithAncestors(address).map(_.filter(caller.identities))

  /**
    * Fetches the ACL resource with the passed ''address'' on the passed revision. The response only contains ACL with
    * identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    * @param rev
    *   the revision to fetch
    */
  final def fetchSelfAt(address: AclAddress, rev: Int)(implicit
      caller: Caller
  ): IO[AclRejection.NotFound, AclResource] =
    fetchAt(address, rev).map(filterSelf)

  /**
    * Fetches the ACL with the passed ''address''. If the [[Acl]] does not exist, return an Acl with empty identity and
    * permissions.
    *
    * @param address
    *   the ACL address
    */
  final def fetchAcl(address: AclAddress): UIO[Acl] =
    fetch(address).attempt.map {
      case Left(AclNotFound(_)) => Acl(address)
      case Right(resource)      => resource.value
    }

  /**
    * Fetches the ACL with the passed ''address''. If the [[Acl]] does not exist, return an Acl with empty identity and
    * permissions. The response only contains ACL with identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    */
  final def fetchSelfAcl(address: AclAddress)(implicit caller: Caller): UIO[Acl] =
    fetchSelf(address).attempt.map {
      case Left(AclNotFound(_)) => Acl(address)
      case Right(resource)      => resource.value
    }

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address.
    *
    * @param filter
    *   the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    */
  def list(filter: AclAddressFilter): UIO[AclCollection]

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address with identities present in the ''caller''.
    *
    * @param filter
    *   the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    * @param caller
    *   the caller that contains the provided identities
    */
  def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection]

  /**
    * A non terminating stream of events for ACLs. After emitting all known events it sleeps until new events are
    * recorded.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def events(offset: Offset = Offset.Start): EnvelopeStream[AclAddress, AclEvent]

  /**
    * The current ACLs events. The stream stops after emitting all known events.
    *
    * @param offset
    *   the last seen event offset; it will not be emitted by the stream
    */
  def currentEvents(offset: Offset = Offset.Start): EnvelopeStream[AclAddress, AclEvent]

  /**
    * Overrides ''acl''.
    *
    * @param acl
    *   the acl to replace
    * @param rev
    *   the last known revision of the resource
    */
  def replace(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Appends ''acl''.
    *
    * @param acl
    *   the acl to append
    * @param rev
    *   the last known revision of the resource
    */
  def append(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Subtracts ''acl''.
    *
    * @param acl
    *   the acl to subtract
    * @param rev
    *   the last known revision of the resource
    */
  def subtract(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclRejection, AclResource]

  /**
    * Delete all ''acl'' on the passed ''address''.
    *
    * @param address
    *   the ACL address
    * @param rev
    *   the last known revision of the resource
    */
  def delete(address: AclAddress, rev: Int)(implicit caller: Subject): IO[AclRejection, AclResource]

  private def filterSelf(resource: AclResource)(implicit caller: Caller): AclResource =
    resource.map(_.filter(caller.identities))

}

object Acls {

  /**
    * The organizations module type.
    */
  final val entityType: EntityType = EntityType("acl")

  def findUnknownRealms(labels: Set[Label], existing: Set[Label]): IO[UnknownRealms, Unit] = {
    val unknown = labels.diff(existing)
    IO.raiseWhen(unknown.nonEmpty)(UnknownRealms(unknown))
  }

  private[delta] def next(state: Option[AclState], event: AclEvent): Option[AclState] = {
    def replaced(e: AclReplaced): AclState =
      state.fold(AclState(e.acl, 1, e.instant, e.subject, e.instant, e.subject)) { c =>
        c.copy(acl = e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }

    def appended(e: AclAppended): AclState =
      state.fold(AclState(e.acl, 1, e.instant, e.subject, e.instant, e.subject)) { c =>
        c.copy(acl = c.acl ++ e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }

    def subtracted(e: AclSubtracted): Option[AclState] =
      state.map { c =>
        c.copy(acl = c.acl -- e.acl, rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }

    def deleted(e: AclDeleted): Option[AclState] =
      state.map { c =>
        c.copy(acl = Acl(c.acl.address), rev = e.rev, updatedAt = e.instant, updatedBy = e.subject)
      }

    event match {
      case ev: AclReplaced   => Some(replaced(ev))
      case ev: AclAppended   => Some(appended(ev))
      case ev: AclSubtracted => subtracted(ev)
      case ev: AclDeleted    => deleted(ev)
    }
  }

  private[delta] def evaluate(
      fetchPermissionSet: UIO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[UnknownRealms, Unit]
  )(state: Option[AclState], cmd: AclCommand)(implicit clock: Clock[UIO] = IO.clock): IO[AclRejection, AclEvent] = {

    def acceptChecking(acl: Acl)(f: Instant => AclEvent) =
      fetchPermissionSet.flatMap { permissions =>
        IO.when(!acl.permissions.subsetOf(permissions))(
          IO.raiseError(UnknownPermissions(acl.permissions -- permissions))
        )
      } >>
        findUnknownRealms(acl.value.keySet.collect { case id: IdentityRealm => id.realm }) >>
        instant.map(f)

    def replace(c: ReplaceAcl)   =
      state match {
        case None if c.rev != 0                                       =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, 0))
        case None if c.acl.hasEmptyPermissions                        =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.acl.address))
        case None                                                     =>
          acceptChecking(c.acl)(AclReplaced(c.acl, 1, _, c.subject))
        case Some(s) if !s.acl.isEmpty && c.rev != s.rev              =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, s.rev))
        case Some(s) if s.acl.isEmpty && c.rev != s.rev && c.rev != 0 =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, s.rev))
        case Some(_) if c.acl.hasEmptyPermissions                     =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.acl.address))
        case Some(s)                                                  =>
          acceptChecking(c.acl)(AclReplaced(c.acl, s.rev + 1, _, c.subject))
      }
    def append(c: AppendAcl)     =
      state match {
        case None if c.rev != 0L                                                 =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, 0))
        case None if c.acl.hasEmptyPermissions                                   =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.acl.address))
        case None                                                                =>
          acceptChecking(c.acl)(AclAppended(c.acl, c.rev + 1, _, c.subject))
        case Some(s) if s.acl.permissions.nonEmpty && c.rev != s.rev             =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, s.rev))
        case Some(s) if s.acl.permissions.isEmpty && c.rev != s.rev & c.rev != 0 =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, s.rev))
        case Some(_) if c.acl.hasEmptyPermissions                                =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.acl.address))
        case Some(s) if s.acl ++ c.acl == s.acl                                  =>
          IO.raiseError(NothingToBeUpdated(c.acl.address))
        case Some(s)                                                             =>
          acceptChecking(c.acl)(AclAppended(c.acl, s.rev + 1, _, c.subject))
      }
    def subtract(c: SubtractAcl) =
      state match {
        case None                                 =>
          IO.raiseError(AclNotFound(c.acl.address))
        case Some(s) if c.rev != s.rev            =>
          IO.raiseError(IncorrectRev(c.acl.address, c.rev, s.rev))
        case Some(_) if c.acl.hasEmptyPermissions =>
          IO.raiseError(AclCannotContainEmptyPermissionCollection(c.acl.address))
        case Some(s) if s.acl -- c.acl == s.acl   =>
          IO.raiseError(NothingToBeUpdated(c.acl.address))
        case Some(_)                              =>
          acceptChecking(c.acl)(AclSubtracted(c.acl, c.rev + 1, _, c.subject))
      }
    def delete(c: DeleteAcl)     =
      state match {
        case None                      => IO.raiseError(AclNotFound(c.address))
        case Some(s) if c.rev != s.rev => IO.raiseError(IncorrectRev(c.address, c.rev, s.rev))
        case Some(s) if s.acl.isEmpty  => IO.raiseError(AclIsEmpty(c.address))
        case Some(_)                   => instant.map(AclDeleted(c.address, c.rev + 1, _, c.subject))
      }

    cmd match {
      case c: ReplaceAcl  => replace(c)
      case c: AppendAcl   => append(c)
      case c: SubtractAcl => subtract(c)
      case c: DeleteAcl   => delete(c)
    }
  }

  /**
    * Entity definition for [[Acls]]
    */
  def definition(
      fetchPermissionSet: UIO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[UnknownRealms, Unit]
  )(implicit
      clock: Clock[UIO] = IO.clock
  ): GlobalEntityDefinition[AclAddress, AclState, AclCommand, AclEvent, AclRejection] =
    GlobalEntityDefinition(
      entityType,
      StateMachine(None, evaluate(fetchPermissionSet, findUnknownRealms), next),
      AclEvent.serializer,
      AclState.serializer,
      onUniqueViolation = (address: AclAddress, c: AclCommand) =>
        c match {
          case c => IncorrectRev(address, c.rev, c.rev + 1)
        }
    )
}
