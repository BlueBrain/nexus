package ch.epfl.bluebrain.nexus.delta.sdk.acls

import cats.effect.{Clock, IO}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.AclResource
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclCommand.{AppendAcl, DeleteAcl, ReplaceAcl, SubtractAcl}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclEvent.{AclAppended, AclDeleted, AclReplaced, AclSubtracted}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclRejection._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model._
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.ProjectDeletionTask
import ch.epfl.bluebrain.nexus.delta.sdk.deletion.model.ProjectDeletionReport
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceUris
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{IdentityRealm, Subject}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.{CommandEvaluator, GlobalEntityDefinition, StateMachine}

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
  def fetch(address: AclAddress): IO[AclResource]

  /**
    * Fetches the ACL resource for an ''address'' and its ancestors on the current revision.
    *
    * @param address
    *   the ACL address
    */
  def fetchWithAncestors(address: AclAddress): IO[AclCollection] = {

    def recurseOnParentAddress(current: AclCollection) =
      address.parent match {
        case Some(parent) => fetchWithAncestors(parent).map(_ ++ current)
        case None         => IO.pure(current)
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
  def fetchAt(address: AclAddress, rev: Int): IO[AclResource]

  /**
    * Fetches the ACL resource with the passed ''address'' on the current revision. The response only contains ACL with
    * identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    */
  final def fetchSelf(address: AclAddress)(implicit caller: Caller): IO[AclResource] =
    fetch(address).map(filterSelf)

  /**
    * Fetches the ACL resource with the passed ''address'' and its ancestors on the current revision. The response only
    * contains ACL with identities present in the provided ''caller''.
    *
    * @param address
    *   the ACL address
    */
  def fetchSelfWithAncestors(address: AclAddress)(implicit caller: Caller): IO[AclCollection] =
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
  ): IO[AclResource] =
    fetchAt(address, rev).map(filterSelf)

  /**
    * Fetches the ACL with the passed ''address''. If the [[Acl]] does not exist, return an Acl with empty identity and
    * permissions.
    *
    * @param address
    *   the ACL address
    */
  final def fetchAcl(address: AclAddress): IO[Acl] =
    fetch(address).attemptNarrow[AclNotFound].map {
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
  final def fetchSelfAcl(address: AclAddress)(implicit caller: Caller): IO[Acl] =
    fetchSelf(address).attemptNarrow[AclNotFound].map {
      case Left(AclNotFound(_)) => Acl(address)
      case Right(resource)      => resource.value
    }

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address.
    *
    * @param filter
    *   the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    */
  def list(filter: AclAddressFilter): IO[AclCollection]

  /**
    * Fetches the [[AclCollection]] of the provided ''filter'' address with identities present in the ''caller''.
    *
    * @param filter
    *   the ACL filter address. All [[AclAddress]] matching the provided filter will be returned
    * @param caller
    *   the caller that contains the provided identities
    */
  def listSelf(filter: AclAddressFilter)(implicit caller: Caller): IO[AclCollection]

  /**
    * Overrides ''acl''.
    *
    * @param acl
    *   the acl to replace
    * @param rev
    *   the last known revision of the resource
    */
  def replace(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource]

  /**
    * Appends ''acl''.
    *
    * @param acl
    *   the acl to append
    * @param rev
    *   the last known revision of the resource
    */
  def append(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource]

  /**
    * Subtracts ''acl''.
    *
    * @param acl
    *   the acl to subtract
    * @param rev
    *   the last known revision of the resource
    */
  def subtract(acl: Acl, rev: Int)(implicit caller: Subject): IO[AclResource]

  /**
    * Delete all ''acl'' on the passed ''address''.
    *
    * @param address
    *   the ACL address
    * @param rev
    *   the last known revision of the resource
    */
  def delete(address: AclAddress, rev: Int)(implicit caller: Subject): IO[AclResource]

  /**
    * Hard deletes events and states for the given acl address. This is meant to be used internally for project and
    * organization deletion.
    */
  def purge(project: AclAddress): IO[Unit]

  private def filterSelf(resource: AclResource)(implicit caller: Caller): AclResource =
    resource.map(_.filter(caller.identities))

}

object Acls {

  /**
    * The organizations module type.
    */
  final val entityType: EntityType = EntityType("acl")

  /**
    * Encode the acl address as an uri
    */
  def encodeId(address: AclAddress): Iri = ResourceUris.acl(address).relativeAccessUri.toIri

  def findUnknownRealms(labels: Set[Label], existing: Set[Label]): IO[Unit] = {
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
      fetchPermissionSet: IO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[Unit],
      clock: Clock[IO]
  )(state: Option[AclState], cmd: AclCommand): IO[AclEvent] = {

    def acceptChecking(acl: Acl)(f: Instant => AclEvent) =
      fetchPermissionSet.flatMap { permissions =>
        IO.raiseWhen(!acl.permissions.subsetOf(permissions))(UnknownPermissions(acl.permissions -- permissions))
      } >>
        findUnknownRealms(acl.value.keySet.collect { case id: IdentityRealm => id.realm }) >>
        clock.realTimeInstant.map(f)

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
        case Some(_)                   => clock.realTimeInstant.map(AclDeleted(c.address, c.rev + 1, _, c.subject))
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
      fetchPermissionSet: IO[Set[Permission]],
      findUnknownRealms: Set[Label] => IO[Unit],
      clock: Clock[IO]
  ): GlobalEntityDefinition[AclAddress, AclState, AclCommand, AclEvent, AclRejection] = {
    val stateMachine = StateMachine(None, next)
    val evaluator    = CommandEvaluator(stateMachine, evaluate(fetchPermissionSet, findUnknownRealms, clock))

    GlobalEntityDefinition(
      entityType,
      evaluator,
      AclEvent.serializer,
      AclState.serializer,
      onUniqueViolation = (address: AclAddress, c: AclCommand) =>
        c match {
          case c => IncorrectRev(address, c.rev, c.rev + 1)
        }
    )
  }

  /**
    * Project deletion task to delete the related ACL. If the project is deleted, we don't want it to inherit the former
    * acls
    */
  def projectDeletionTask(acls: Acls): ProjectDeletionTask = new ProjectDeletionTask {
    override def apply(project: ProjectRef)(implicit subject: Subject): IO[ProjectDeletionReport.Stage] = {
      val report = ProjectDeletionReport.Stage("acls", "The acl has been deleted.")
      acls.purge(project).as(report)
    }
  }
}
