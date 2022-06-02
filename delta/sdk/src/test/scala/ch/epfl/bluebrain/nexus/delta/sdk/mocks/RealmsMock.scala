package ch.epfl.bluebrain.nexus.delta.sdk.mocks

import akka.http.scaladsl.model.Uri
import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.RealmNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{RealmEvent, RealmRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, SearchParams, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Name, NonEmptySet}
import ch.epfl.bluebrain.nexus.delta.sdk.{RealmResource, Realms}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import monix.bio.{IO, Task, UIO}

/**
  * Partial dummy implementation, that only implements the fetch a realm
  */
class RealmsMock(expected: Map[Label, RealmResource]) extends Realms {
  // format: off

  override def create(label: Label, name: Name, openIdConfig: Uri, logo: Option[Uri], acceptedAudiences: Option[NonEmptySet[String]])(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] = ???

  override def update(label: Label, rev: Long, name: Name, openIdConfig: Uri, logo: Option[Uri], acceptedAudiences: Option[NonEmptySet[String]])(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] = ???

  override def deprecate(label: Label, rev: Long)(implicit caller: Identity.Subject): IO[RealmRejection, RealmResource] = ???

  override def fetch(label: Label): IO[RealmRejection.RealmNotFound, RealmResource] =
    IO.fromEither(expected.get(label).toRight(RealmNotFound(label)))

  override def fetchAt(label: Label, rev: Long): IO[RealmRejection.NotFound, RealmResource] = ???

  override def list(pagination: Pagination.FromPagination, params: SearchParams.RealmSearchParams, ordering: Ordering[RealmResource]): UIO[SearchResults.UnscoredSearchResults[RealmResource]] = ???

  override def events(offset: Offset): fs2.Stream[Task, Envelope[RealmEvent]] = ???

  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[RealmEvent]] = ???
  // format: on
}
