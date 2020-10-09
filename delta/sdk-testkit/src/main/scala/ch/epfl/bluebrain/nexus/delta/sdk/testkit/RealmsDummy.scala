package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.http.scaladsl.model.Uri
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RevisionNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.UnscoredResultEntry
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsDummy.{RealmsCache, RealmsJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import monix.bio.{IO, UIO}

/**
  * A dummy Realms implementation that uses a synchronized in memory journal.
  *
  * @param resolveWellKnown get the well known configuration for an OIDC provider resolver
  * @param journal   a ref to the journal containing all the events discriminated by label
  * @param cache     a ref to the cache containing all the current realm resources
  * @param semaphore a semaphore for serializing write operations on the journal
  */
final class RealmsDummy private (
    resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
    journal: IORef[RealmsJournal],
    cache: IORef[RealmsCache],
    semaphore: IOSemaphore
)(implicit clock: Clock[UIO])
    extends Realms {

  override def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(CreateRealm(label, name, openIdConfig, logo, caller)).flatMap(setToCache)

  override def update(
      label: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(UpdateRealm(label, rev, name, openIdConfig, logo, caller)).flatMap(setToCache)

  override def deprecate(label: Label, rev: Long)(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller)).flatMap(deprecateFromCache)

  override def fetch(label: Label): UIO[Option[RealmResource]] =
    currentState(label).map(_.flatMap(_.toResource))

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.RevisionNotFound, Option[RealmResource]] =
    stateAt(label, rev).map(_.flatMap(_.toResource))

  override def list(pagination: FromPagination, params: RealmSearchParams): UIO[UnscoredSearchResults[RealmResource]] =
    cache.get.map { resources =>
      val filtered = resources.values
        .filter { resource =>
          params.rev.forall(_ == resource.rev) &&
          params.deprecated.forall(_ && resource.deprecated) &&
          params.createdBy.forall(_ == resource.createdBy) &&
          params.updatedBy.forall(_ == resource.updatedBy)
        }
        .toVector
        .sortBy(_.createdAt)
      UnscoredSearchResults(
        filtered.length.toLong,
        filtered.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }

  private def setToCache(resource: RealmResource): UIO[RealmResource]         =
    cache.update(_ + (resource.id -> resource)).as(resource)

  private def deprecateFromCache(resource: RealmResource): UIO[RealmResource] =
    cache.update(_.updatedWith(resource.id)(_.as(resource))).as(resource)

  private def currentState(label: Label): UIO[Option[RealmState]] =
    journal.get.map { labelsEvents =>
      labelsEvents.get(label).map(_.foldLeft[RealmState](Initial)(Realms.next))
    }

  private def stateAt(label: Label, rev: Long): IO[RevisionNotFound, Option[RealmState]] =
    journal.get.flatMap { labelsEvents =>
      labelsEvents.get(label).traverse { events =>
        if (events.size < rev)
          IO.raiseError(RevisionNotFound(rev, events.size.toLong))
        else
          events
            .foldLeft[RealmState](Initial) {
              case (state, event) if event.rev <= rev => Realms.next(state, event)
              case (state, _)                         => state
            }
            .pure[UIO]
      }
    }

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    semaphore.withPermit {
      for {
        lbEvents <- journal.get
        state     = lbEvents.get(cmd.label).fold[RealmState](Initial)(_.foldLeft[RealmState](Initial)(Realms.next))
        event    <- Realms.evaluate(resolveWellKnown, cache.get.map(_.values.toSet))(state, cmd)
        _        <- journal.set(lbEvents.updatedWith(cmd.label)(_.fold(Some(Vector(event)))(events => Some(events :+ event))))
        res      <- IO.fromEither(Realms.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
      } yield res
    }
}

object RealmsDummy {

  type RealmsJournal = Map[Label, Vector[RealmEvent]]
  type RealmsCache   = Map[Label, RealmResource]

  /**
    * Creates a new dummy Realms implementation.
    *
    * @param resolveWellKnown the well known configuration for an OIDC provider resolver
    */
  final def apply(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown]
  )(implicit clock: Clock[UIO]): UIO[RealmsDummy] =
    for {
      journalRef <- IORef.of[RealmsJournal](Map.empty)
      cacheRef   <- IORef.of[RealmsCache](Map.empty)
      sem        <- IOSemaphore(1L)
    } yield new RealmsDummy(resolveWellKnown, journalRef, cacheRef, sem)
}
