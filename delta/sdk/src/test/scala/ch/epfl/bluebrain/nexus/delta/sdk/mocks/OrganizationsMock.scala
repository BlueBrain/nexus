package ch.epfl.bluebrain.nexus.delta.sdk.mocks

import java.util.UUID

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.{OrganizationEvent, OrganizationRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, Organizations}
import monix.bio.{IO, Task, UIO}

/**
  * Partial dummy implementation, that only implements the fetch of an organization
  *
  * @param expected the expected results as a map where the key is the organization label
  *                 and the value is the expected organization resource
  */
class OrganizationsMock(expected: Map[Label, OrganizationResource]) extends Organizations {
  // format: off
  override def create(label: Label, description: Option[String])(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def update(label: Label, description: Option[String], rev: Long)(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def deprecate(label: Label, rev: Long)(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def fetchAt(label: Label, rev: Long): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] = ???
  override def fetchAt(uuid: UUID, rev: Long): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] = ???
  override def list(pagination: Pagination.FromPagination, params: SearchParams.OrganizationSearchParams): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] = ???
  override def events(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] = ???
  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[OrganizationEvent]] = ???
  // format: on

  override def fetch(uuid: UUID): UIO[Option[OrganizationResource]]   =
    IO.pure(expected.collectFirst({ case (_, v) if v.value.uuid == uuid => v }))
  override def fetch(label: Label): UIO[Option[OrganizationResource]] =
    IO.pure(expected.get(label))
}
