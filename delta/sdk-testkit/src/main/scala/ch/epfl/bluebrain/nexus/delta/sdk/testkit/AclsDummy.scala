package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.AclsDummy.AclsJournal
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import monix.bio.{IO, UIO}

/**
  * A dummy ACLs implementation that uses a synchronized in memory journal.
  *
  * @param perms     the bundle of operations pertaining to managing permissions wrappd in an IO
  * @param journal   a ref to the journal containing all the events discriminated by [[AclAddress]] location
  * @param cache     a ref to the cache containing all the current acl resources
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class AclsDummy private (
    perms: UIO[Permissions],
    journal: IORef[AclsJournal],
    cache: IORef[AclCollection],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO])
    extends Acls {

  override def fetch(address: AclAddress): UIO[Option[AclResource]] =
    currentState(address).map(_.flatMap(_.toResource))

  override def fetchAt(address: AclAddress, rev: Long): IO[RevisionNotFound, Option[AclResource]] =
    stateAt(address, rev).map(_.flatMap(_.toResource))

  override def list(filter: AclAddressFilter): UIO[AclCollection] =
    cache.get.map(_.fetch(filter))

  override def listSelf(filter: AclAddressFilter)(implicit caller: Caller): UIO[AclCollection] =
    list(filter).map(_.filter(caller.identities))

  override def replace(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(ReplaceAcl(address, acl, rev, caller)).flatMap(setToCache)

  override def append(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(AppendAcl(address, acl, rev, caller)).flatMap(appendToCache)

  override def subtract(address: AclAddress, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(SubtractAcl(address, acl, rev, caller)).flatMap(subtractFromCache)

  override def delete(address: AclAddress, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(DeleteAcl(address, rev, caller)).flatMap(deleteFromCache)

  private def setToCache(resource: AclResource): UIO[AclResource]    =
    cache.update(c => c.copy(c.value + (resource.id -> resource))).as(resource)

  private def appendToCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ + resource).as(resource)

  private def subtractFromCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ - resource).as(resource)

  private def deleteFromCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ - resource.id).as(resource)

  private def currentState(address: AclAddress): UIO[Option[AclState]] =
    journal.get.map { labelsEvents =>
      labelsEvents.get(address).map(_.foldLeft[AclState](Initial)(Acls.next))
    }

  private def stateAt(address: AclAddress, rev: Long): IO[RevisionNotFound, Option[AclState]] =
    journal.get.flatMap { labelsEvents =>
      labelsEvents.get(address).traverse { events =>
        if (events.size < rev)
          IO.raiseError(RevisionNotFound(rev, events.size.toLong))
        else
          events
            .foldLeft[AclState](Initial) {
              case (state, event) if event.rev <= rev => Acls.next(state, event)
              case (state, _)                         => state
            }
            .pure[UIO]
      }
    }

  private def eval(cmd: AclCommand): IO[AclRejection, AclResource] =
    semaphore.withPermit {
      for {
        tgEvents <- journal.get
        state     = tgEvents.get(cmd.address).fold[AclState](Initial)(_.foldLeft[AclState](Initial)(Acls.next))
        event    <- Acls.evaluate(perms)(state, cmd)
        _        <-
          journal.set(tgEvents.updatedWith(cmd.address)(_.fold(Some(Vector(event)))(events => Some(events :+ event))))
        res      <- IO.fromEither(Acls.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.address)))
      } yield res
    }
}

object AclsDummy {

  type AclsJournal = Map[AclAddress, Vector[AclEvent]]

  /**
    * Creates a new dummy Acls implementation.
    *
   * @param perms the bundle of operations pertaining to managing permissions wrapped in an IO
    *
   */
  final def apply(perms: UIO[Permissions])(implicit clock: Clock[UIO] = IO.clock): UIO[AclsDummy] =
    for {
      journalRef <- IORef.of[AclsJournal](Map.empty)
      cacheRef   <- IORef.of(AclCollection.empty)
      sem        <- IOSemaphore(1L)
    } yield new AclsDummy(perms, journalRef, cacheRef, sem)
}
