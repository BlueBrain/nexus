package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand.{CreateOrganization, DeprecateOrganization, UpdateOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.UnexpectedInitialState
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{OrganizationCommand, OrganizationRejection, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.OrganizationsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, OrganizationResource, Organizations}
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Organizations implementation that uses a synchronized in memory journal.
  *
  * @param journal     a ref to the journal containing all the events wrapped in an envelope
  * @param cache       a ref to the cache containing all the current organization resources
  */
final class OrganizationsDummy private (
    journal: OrganizationsJournal,
    cache: OrganizationsCache
)(implicit clock: Clock[UIO], uuidF: UUIDF)
    extends Organizations {

  override def create(
      label: Label,
      description: Option[String]
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(CreateOrganization(label, description, caller))

  override def update(
      label: Label,
      description: Option[String],
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(UpdateOrganization(label, rev, description, caller))

  override def deprecate(
      label: Label,
      rev: Long
  )(implicit caller: Subject): IO[OrganizationRejection, OrganizationResource] =
    eval(DeprecateOrganization(label, rev, caller))

  override def fetch(label: Label): UIO[Option[OrganizationResource]] =
    cache.fetch(label)

  override def fetchAt(
      label: Label,
      rev: Long
  ): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] =
    journal
      .stateAt(label, rev, Initial, Organizations.next, OrganizationRejection.RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))

  override def fetch(uuid: UUID): UIO[Option[OrganizationResource]] =
    cache.fetchBy(o => o.uuid == uuid)

  override def fetchAt(
      uuid: UUID,
      rev: Long
  ): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] =
    fetch(uuid).flatMap {
      case Some(value) => fetchAt(value.id, rev)
      case None        => IO.pure(None)
    }

  override def list(
      pagination: Pagination.FromPagination,
      params: OrganizationSearchParams
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    cache.list(pagination, params)

  override def events(offset: Offset = NoOffset): Stream[Task, Envelope[OrganizationEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[OrganizationEvent]] =
    journal.currentEvents(offset)

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] =
    for {
      state <- journal.currentState(cmd.label, Initial, Organizations.next).map(_.getOrElse(Initial))
      event <- Organizations.evaluate(state, cmd)
      _     <- journal.add(event)
      res   <- IO.fromEither(Organizations.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
      _     <- cache.setToCache(res)
    } yield res
}

object OrganizationsDummy {

  type OrganizationsJournal = Journal[Label, OrganizationEvent]
  type OrganizationsCache   = ResourceCache[Label, Organization]

  val entityType: String = "organizations"

  implicit val idLens: Lens[OrganizationEvent, Label] = (a: OrganizationEvent) => a.label

  /**
    * Creates a new dummy Organizations implementation.
    */
  final def apply()(implicit
      uuidF: UUIDF = UUIDF.random,
      clock: Clock[UIO] = IO.clock
  ): UIO[OrganizationsDummy] =
    for {
      journal <- Journal(entityType)
      cache   <- ResourceCache[Label, Organization]
    } yield new OrganizationsDummy(journal, cache)
}
