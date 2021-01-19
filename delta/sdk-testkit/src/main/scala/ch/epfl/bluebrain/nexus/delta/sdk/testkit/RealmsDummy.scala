package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import akka.http.scaladsl.model.Uri
import akka.persistence.query.Offset
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.Realms.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmCommand.{CreateRealm, DeprecateRealm, UpdateRealm}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{RealmNotFound, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.UnscoredSearchResults
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.RealmsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, RealmResource, Realms}
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Realms implementation that uses a synchronized in memory journal.
  *
  * @param journal          the journal to store events
  * @param cache            the cache to store resources
  * @param semaphore        a semaphore for serializing write operations on the journal
  * @param resolveWellKnown get the well known configuration for an OIDC provider resolver
  */
final class RealmsDummy private (
    journal: RealmsJournal,
    cache: RealmsCache,
    semaphore: IOSemaphore,
    resolveWellKnown: Uri => IO[RealmRejection, WellKnown]
)(implicit clock: Clock[UIO])
    extends Realms {
  override def create(
      label: Label,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(CreateRealm(label, name, openIdConfig, logo, caller))

  override def update(
      label: Label,
      rev: Long,
      name: Name,
      openIdConfig: Uri,
      logo: Option[Uri]
  )(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(UpdateRealm(label, rev, name, openIdConfig, logo, caller))

  override def deprecate(label: Label, rev: Long)(implicit caller: Subject): IO[RealmRejection, RealmResource] =
    eval(DeprecateRealm(label, rev, caller))

  override def fetch(label: Label): IO[RealmNotFound, RealmResource] =
    cache.fetchOr(label, RealmNotFound(label))

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.NotFound, RealmResource] =
    journal
      .stateAt(label, rev, Initial, Realms.next, RealmRejection.RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))
      .flatMap(IO.fromOption(_, RealmNotFound(label)))

  override def list(
      pagination: FromPagination,
      params: RealmSearchParams,
      ordering: Ordering[RealmResource]
  ): UIO[UnscoredSearchResults[RealmResource]] =
    cache.list(pagination, params, ordering)

  private def eval(cmd: RealmCommand): IO[RealmRejection, RealmResource] =
    semaphore.withPermit {
      for {
        state <- journal.currentState(cmd.label, Initial, Realms.next).map(_.getOrElse(Initial))
        event <- Realms.evaluate(resolveWellKnown, cache.values)(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Realms.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
        _     <- cache.setToCache(res)
      } yield res
    }

  override def events(offset: Offset): fs2.Stream[Task, Envelope[RealmEvent]] = journal.events(offset)

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[RealmEvent]] = journal.events(offset)
}

object RealmsDummy {

  type RealmsJournal = Journal[Label, RealmEvent]
  type RealmsCache   = ResourceCache[Label, Realm]

  implicit val idLens: Lens[RealmEvent, Label] = (event: RealmEvent) => event.label

  /**
    * Creates a new dummy Realms implementation.
    *
    * @param resolveWellKnown the well known configuration for an OIDC provider resolver
    */
  final def apply(
      resolveWellKnown: Uri => IO[RealmRejection, WellKnown]
  )(implicit clock: Clock[UIO]): UIO[RealmsDummy] = {
    implicit val lens: Lens[Realm, Label] = _.label

    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[Label, Realm]
      sem     <- IOSemaphore(1L)
    } yield new RealmsDummy(journal, cache, sem, resolveWellKnown)
  }
}
