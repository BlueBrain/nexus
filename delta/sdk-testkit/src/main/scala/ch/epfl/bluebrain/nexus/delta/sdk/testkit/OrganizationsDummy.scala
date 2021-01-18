package ch.epfl.bluebrain.nexus.delta.sdk.testkit

import java.util.UUID

import akka.persistence.query.{NoOffset, Offset}
import cats.effect.Clock
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.Organizations.moduleType
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationCommand.{CreateOrganization, DeprecateOrganization, UpdateOrganization}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection.{OrganizationNotFound, OwnerPermissionsFailed, UnexpectedInitialState}
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationState.Initial
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{OrganizationCommand, OrganizationRejection, _}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.OrganizationSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.OrganizationsDummy._
import ch.epfl.bluebrain.nexus.delta.sdk.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.{Lens, OrganizationResource, Organizations}
import ch.epfl.bluebrain.nexus.testkit.IOSemaphore
import fs2.Stream
import monix.bio.{IO, Task, UIO}

/**
  * A dummy Organizations implementation that uses a synchronized in memory journal.
  *
  * @param journal     a ref to the journal containing all the events wrapped in an envelope
  * @param cache       a ref to the cache containing all the current organization resources
  * @param semaphore   a semaphore for serializing write operations on the journal
  */
final class OrganizationsDummy private (
    journal: OrganizationsJournal,
    cache: OrganizationsCache,
    semaphore: IOSemaphore,
    applyOwnerPermissions: ApplyOwnerPermissionsDummy
)(implicit clock: Clock[UIO], uuidF: UUIDF)
    extends Organizations {

  override def create(label: Label, description: Option[String])(implicit
      caller: Subject
  ): IO[OrganizationRejection, OrganizationResource] =
    eval(CreateOrganization(label, description, caller)) <* applyOwnerPermissions
      .onOrganization(label, caller)
      .leftMap(OwnerPermissionsFailed(label, _))

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

  override def fetch(label: Label): IO[OrganizationNotFound, OrganizationResource] =
    cache.fetchOr(label, OrganizationNotFound(label))

  override def fetchAt(label: Label, rev: Long): IO[OrganizationRejection.NotFound, OrganizationResource] =
    journal
      .stateAt(label, rev, Initial, Organizations.next, OrganizationRejection.RevisionNotFound.apply)
      .map(_.flatMap(_.toResource))
      .flatMap(IO.fromOption(_, OrganizationNotFound(label)))

  override def fetch(uuid: UUID): IO[OrganizationNotFound, OrganizationResource] =
    cache.fetchByOr(o => o.uuid == uuid, OrganizationNotFound(uuid))

  override def list(
      pagination: Pagination.FromPagination,
      params: OrganizationSearchParams,
      ordering: Ordering[OrganizationResource]
  ): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] =
    cache.list(pagination, params, ordering)

  override def events(offset: Offset = NoOffset): Stream[Task, Envelope[OrganizationEvent]] =
    journal.events(offset)

  override def currentEvents(offset: Offset): Stream[Task, Envelope[OrganizationEvent]] =
    journal.currentEvents(offset)

  private def eval(cmd: OrganizationCommand): IO[OrganizationRejection, OrganizationResource] = {
    semaphore.withPermit {
      for {
        state <- journal.currentState(cmd.label, Initial, Organizations.next).map(_.getOrElse(Initial))
        event <- Organizations.evaluate(state, cmd)
        _     <- journal.add(event)
        res   <- IO.fromEither(Organizations.next(state, event).toResource.toRight(UnexpectedInitialState(cmd.label)))
        _     <- cache.setToCache(res)
      } yield res
    }
  }
}

object OrganizationsDummy {

  type OrganizationsJournal = Journal[Label, OrganizationEvent]
  type OrganizationsCache   = ResourceCache[Label, Organization]

  implicit private val idLens: Lens[OrganizationEvent, Label] = (a: OrganizationEvent) => a.label

  implicit private val lens: Lens[Organization, Label] = _.label

  final def apply(applyOwnerPermissions: ApplyOwnerPermissionsDummy)(implicit
      uuidF: UUIDF,
      clock: Clock[UIO]
  ): UIO[OrganizationsDummy] =
    for {
      journal <- Journal(moduleType)
      cache   <- ResourceCache[Label, Organization]
      sem     <- IOSemaphore(1L)
    } yield new OrganizationsDummy(journal, cache, sem, applyOwnerPermissions)

  /**
    * Creates a new dummy Organizations implementation.
    */
  final def apply()(implicit
      uuidF: UUIDF,
      clock: Clock[UIO]
  ): UIO[OrganizationsDummy] =
    AclsDummy(PermissionsDummy(Set.empty)).flatMap { acls =>
      apply(ApplyOwnerPermissionsDummy(acls, Set.empty, Identity.Anonymous))
    }

}
