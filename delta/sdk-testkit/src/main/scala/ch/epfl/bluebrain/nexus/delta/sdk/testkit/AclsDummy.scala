package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.persistence.query.Offset
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Acls.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.AclsDummy.AclsJournal
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Lens, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import monix.bio.{IO, Task, UIO}

/**
  * A dummy ACLs implementation that uses a synchronized in memory journal.
  *
  * @param permissions the bundle of operations pertaining to managing permissions
  * @param journal     a ref to the journal containing all the events discriminated by [[AclAddress]] location
  * @param cache       a ref to the cache containing all the current acl resources
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class AclsDummy private (
    permissions: Permissions,
    journal: AclsJournal,
    cache: IORef[AclCollection],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO])
    extends Acls {

  override def fetch(address: AclAddress): UIO[Option[AclResource]] =
    cache.get.map(_.value.get(address))

  override def fetchWithAncestors(address: AclAddress): UIO[AclCollection] =
    Acls.fetchWithAncestors(address, this, permissions)

  override def fetchAt(address: AclAddress, rev: Long): IO[RevisionNotFound, Option[AclResource]] =
    journal
      .stateAt(address, rev, Initial, Acls.next, RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))

  override def list(filter: AclAddressFilter): UIO[AclCollection] =
    cache.get.map(_.fetch(filter))

  override def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection] =
    list(filter).map(_.filter(caller.identities))

  override def events(offset: Offset): fs2.Stream[Task, Envelope[AclEvent]] = journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[AclEvent]] = journal.events(offset)

  override def replace(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(ReplaceAcl(acl, rev, caller)).flatMap(setToCache)

  override def append(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(AppendAcl(acl, rev, caller)).flatMap(setToCache)

  override def subtract(acl: Acl, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(SubtractAcl(acl, rev, caller)).flatMap(setToCache)

  override def delete(address: AclAddress, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(DeleteAcl(address, rev, caller)).flatMap(deleteFromCache)

  private def setToCache(resource: AclResource): UIO[AclResource]      =
    cache.update(c => c.copy(c.value + (resource.value.address -> resource))).as(resource)

  private def deleteFromCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ - resource.value.address).as(resource)

  private def eval(cmd: AclCommand): IO[AclRejection, AclResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState(cmd.address, Initial, Acls.next).map(_.getOrElse(Initial))
        event <- Acls.evaluate(UIO.pure(permissions))(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Acls.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.address)))
      } yield res
    }
}

object AclsDummy {

  type AclsJournal = Journal[AclAddress, AclEvent]

  /**
    * Creates a new dummy Acls implementation.
    *
    * @param permissions the bundle of operations pertaining to managing permissions wrapped in an IO
    */
  final def apply(permissions: UIO[Permissions])(implicit clock: Clock[UIO] = IO.clock): UIO[AclsDummy] = {
    implicit val idLens: Lens[AclEvent, AclAddress] = _.address

    for {
      perms    <- permissions
      journal  <- Journal(moduleType)
      cacheRef <- IORef.of(AclCollection.empty)
      sem      <- IOSemaphore(1L)
    } yield new AclsDummy(perms, journal, cacheRef, sem)
  }
}
