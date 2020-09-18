package ch.epfl.bluebrain.nexus.delta.sdk.dummies

import java.util.UUID

import ch.epfl.bluebrain.nexus.delta.sdk.model.organizations.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.{OrganizationResource, Organizations}
import monix.bio.{IO, UIO}

/**
  * Partial dummy implementation, that only implements the fetch of an organization
  *
  * @param expected the expected results as a map where the key is the organization label
  *                 and the value is the expected organization resource
  */
class OrganizationsDummy(expected: Map[Label, OrganizationResource]) extends Organizations {
  // format: off
  override def create(label: Label, description: Option[String])(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def update(label: Label, description: Option[String], rev: Long)(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def deprecate(label: Label, rev: Long)(implicit caller: Identity.Subject): IO[OrganizationRejection, OrganizationResource] = ???
  override def fetchAt(label: Label, rev: Long): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] = ???
  override def fetchAt(uuid: UUID, rev: Long): IO[OrganizationRejection.RevisionNotFound, Option[OrganizationResource]] = ???
  override def list(pagination: Pagination.FromPagination, params: SearchParams.OrganizationSearchParams): UIO[SearchResults.UnscoredSearchResults[OrganizationResource]] = ???
  // format: on

  override def fetch(uuid: UUID): UIO[Option[OrganizationResource]]   =
    IO.pure(expected.collectFirst({ case (_, v) if v.value.uuid == uuid => v }))
  override def fetch(label: Label): UIO[Option[OrganizationResource]] =
    IO.pure(expected.get(label))
}
