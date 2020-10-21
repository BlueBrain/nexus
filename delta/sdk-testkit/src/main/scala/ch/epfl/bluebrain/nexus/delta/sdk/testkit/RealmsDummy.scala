package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.concurrent.atomic.AtomicLong

import akka.http.scaladsl.model.Uri
import akka.persistence.query.{NoOffset, Offset, Sequence}
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
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsDummy.{RealmsCache, RealmsJournal}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.testkit.{IORef, IOSemaphore}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

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
    semaphore: IOSemaphore,
    maxStreamSize: Long
)(implicit clock: Clock[UIO], base: BaseUri)
    extends Realms {

  private val offsetMax = new AtomicLong()

  private def journalMap: IO[Nothing, Map[Label, Vector[Envelope[RealmEvent]]]] =
    journal.get.map { v =>
      v.groupBy(_.event.label).map {
        case (k, v) =>
          k -> v.sortBy(_.sequenceNr)
      }
    }

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
          params.issuer.forall(_ == resource.value.issuer) &&
          params.rev.forall(_ == resource.rev) &&
          params.deprecated.forall(_ && resource.deprecated) &&
          params.createdBy.forall(_ == resource.createdBy.id) &&
          params.updatedBy.forall(_ == resource.updatedBy.id)
        }
        .toVector
        .sortBy(_.createdAt)
      UnscoredSearchResults(
        filtered.length.toLong,
        filtered.map(UnscoredResultEntry(_)).slice(pagination.from, pagination.from + pagination.size)
      )
    }

  def events(offset: Offset = NoOffset): Stream[Task, Envelope[RealmEvent]] =
    DummyHelpers.eventsFromJournal(
      journal.get,
      offset,
      maxStreamSize
    )

  private def setToCache(resource: RealmResource): UIO[RealmResource]         =
    cache.update(_ + (resource.id -> resource)).as(resource)

  private def deprecateFromCache(resource: RealmResource): UIO[RealmResource] =
    cache.update(_.updatedWith(resource.id)(_.as(resource))).as(resource)

  private def currentState(label: Label): UIO[Option[RealmState]] =
    journalMap.map { labelsEvents =>
      labelsEvents
        .get(label)
        .map(_.foldLeft[RealmState](Initial) {
          case (s, e) =>
            Realms.next(s, e.event)
        })
    }

  private def stateAt(label: Label, rev: Long): IO[RevisionNotFound, Option[RealmState]] =
    journalMap.flatMap { labelsEvents =>
      labelsEvents.get(label).traverse { events =>
        if (events.size < rev)
          IO.raiseError(RevisionNotFound(rev, events.size.toLong))
        else
          events
            .foldLeft[RealmState](Initial) {
              case (state, envelope) if envelope.event.rev <= rev => Realms.next(state, envelope.event)
              case (state, _)                                     => state
            }
            .pure[UIO]
      }
    }

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    semaphore.withPermit {
      for {
        lbEvents <- journal.get
        state    <- currentState(cmd.label).map(_.getOrElse(Initial))
        event    <- Realms.evaluate(resolveWellKnown, cache.get.map(_.values.toSet))(state, cmd)
        _        <- journal.set(lbEvents :+ makeEnvelope(event))
        res      <- IO.fromEither(Realms.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
      } yield res
    }

  private def makeEnvelope(event: RealmEvent): Envelope[RealmEvent] = {
    Envelope(
      event,
      event.getClass.getSimpleName,
      Sequence(offsetMax.incrementAndGet()),
      s"realms-${event.label}",
      event.rev,
      event.instant.toEpochMilli
    )
  }

}

object RealmsDummy {

  type RealmsJournal = Vector[Envelope[RealmEvent]]
  type RealmsCache   = Map[Label, RealmResource]

  /**
    * Creates a new dummy Realms implementation.
    *
    * @param resolveWellKnown the well known configuration for an OIDC provider resolver
    * @param maxStreamSize truncate event stream after this size
    */
  final def apply(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown],
      maxStreamSize: Long = Long.MaxValue
  )(implicit clock: Clock[UIO], base: BaseUri): UIO[RealmsDummy] =
    for {
      journalRef <- IORef.of[RealmsJournal](Vector.empty)
      cacheRef   <- IORef.of[RealmsCache](Map.empty)
      sem        <- IOSemaphore(1L)
    } yield new RealmsDummy(resolveWellKnown, journalRef, cacheRef, sem, maxStreamSize)
}
