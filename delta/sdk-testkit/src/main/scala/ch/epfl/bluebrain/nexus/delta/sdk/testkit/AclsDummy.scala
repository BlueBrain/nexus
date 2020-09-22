package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclCommand._
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.Target.TargetLocation
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.AclsDummy.AclsJournal
import ch.epfl.bluebrain.nexus.delta.sdk.{AclResource, Acls, Permissions}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import monix.bio.{IO, UIO}

/**
  * A dummy ACLs implementation that uses a synchronized in memory journal.
  *
  * @param perms     the bundle of operations pertaining to managing permissions wrappd in an IO
  * @param journal   a ref to the journal containing all the events discriminated by [[Target]] location
  * @param cache     a ref to the cache containing all the current acl resources
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class AclsDummy private (
    perms: UIO[Permissions],
    journal: IORef[AclsJournal],
    cache: IORef[AclTargets],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO])
    extends Acls {

  override def fetch(target: TargetLocation): UIO[Option[AclResource]] =
    currentState(target).map(_.flatMap(_.toResource))

  override def fetchAt(target: TargetLocation, rev: Long): IO[RevisionNotFound, Option[AclResource]] =
    stateAt(target, rev).map(_.flatMap(_.toResource))

  override def list(target: Target, ancestors: Boolean): UIO[AclTargets] =
    if (ancestors) listWithAncestors(target)
    else listWithoutAncestors(target)

  override def listSelf(target: Target, ancestors: Boolean)(implicit caller: Caller): UIO[AclTargets] =
    list(target, ancestors).map(_.filter(caller.identities))

  private def listWithoutAncestors(target: Target): UIO[AclTargets] =
    cache.get.map(_.fetch(target))

  private def listWithAncestors(target: Target): UIO[AclTargets] =
    listWithoutAncestors(target)
      .flatMap(result => target.parent.fold(UIO.pure(result))(parent => listWithAncestors(parent).map(result ++ _)))

  override def replace(target: TargetLocation, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(ReplaceAcl(target, acl, rev, caller)).flatMap(setToCache)

  override def append(target: TargetLocation, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(AppendAcl(target, acl, rev, caller)).flatMap(appendToCache)

  override def subtract(target: TargetLocation, acl: Acl, rev: Long)(implicit
      caller: Subject
  ): IO[AclRejection, AclResource] =
    eval(SubtractAcl(target, acl, rev, caller)).flatMap(subtractFromCache)

  override def delete(target: TargetLocation, rev: Long)(implicit caller: Subject): IO[AclRejection, AclResource] =
    eval(DeleteAcl(target, rev, caller)).flatMap(deleteFromCache(target, _))

  private def setToCache(resource: AclResource): UIO[AclResource]    =
    cache.update(c => c.copy(c.value + (resource.id -> resource))).as(resource)

  private def appendToCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ + resource).as(resource)

  private def subtractFromCache(resource: AclResource): UIO[AclResource] =
    cache.update(_ - resource).as(resource)

  private def deleteFromCache(target: TargetLocation, resource: AclResource): UIO[AclResource] =
    cache.update(_ - target).as(resource)

  private def currentState(target: TargetLocation): UIO[Option[AclState]] =
    journal.get.map { labelsEvents =>
      labelsEvents.get(target).map(_.foldLeft[AclState](Initial)(Acls.next))
    }

  private def stateAt(target: Target, rev: Long): IO[RevisionNotFound, Option[AclState]] =
    journal.get.flatMap { labelsEvents =>
      labelsEvents.get(target).traverse { events =>
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
        state     = tgEvents.get(cmd.target).fold[AclState](Initial)(_.foldLeft[AclState](Initial)(Acls.next))
        event    <- Acls.evaluate(perms)(state, cmd)
        _        <- journal.set(tgEvents.updatedWith(cmd.target)(_.fold(Some(Vector(event)))(events => Some(events :+ event))))
        res      <- IO.fromEither(Acls.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.target)))
      } yield res
    }
}

object AclsDummy {

  type AclsJournal = Map[Target, Vector[AclEvent]]

  /**
    * Creates a new dummy Acls implementation.
    *
   * @param perms the bundle of operations pertaining to managing permissions wrapped in an IO
    *
   */
  final def apply(perms: UIO[Permissions])(implicit clock: Clock[UIO] = IO.clock): UIO[AclsDummy] =
    for {
      journalRef <- IORef.of[AclsJournal](Map.empty)
      cacheRef   <- IORef.of(AclTargets.empty)
      sem        <- IOSemaphore(1L)
    } yield new AclsDummy(perms, journalRef, cacheRef, sem)
}
