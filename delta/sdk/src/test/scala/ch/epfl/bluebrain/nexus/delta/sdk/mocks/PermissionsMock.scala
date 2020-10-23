package ch.epfl.bluebrain.nexus.delta.sdk.mocks

import akka.persistence.query.Offset
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsEvent, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import monix.bio.{IO, Task, UIO}

/**
  * Partial dummy implementation, that only implements the fetch of all the permissions
  *
  * @param expected the expected result of fetching all the permissions
  */
class PermissionsMock(expected: PermissionsResource) extends Permissions {
  // format: off
  override def persistenceId: String                                                                            = ???
  override def minimum: Set[Permission]                                                                         = ???
  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource]               = ???
  override def replace(permissions: Set[Permission], rev: Long)(implicit caller: Identity.Subject): IO[PermissionsRejection, PermissionsResource] = ???
  override def append(permissions: Set[Permission], rev: Long)(implicit caller: Identity.Subject): IO[PermissionsRejection, PermissionsResource] = ???
  override def subtract(permissions: Set[Permission], rev: Long)(implicit caller: Identity.Subject): IO[PermissionsRejection, PermissionsResource] = ???
  override def delete(rev: Long)(implicit caller: Identity.Subject): IO[PermissionsRejection, PermissionsResource] = ???
  override def events(offset: Offset): fs2.Stream[Task, Envelope[PermissionsEvent]] = ???
  override def currentEvents(offset: Offset): fs2.Stream[Task, Envelope[PermissionsEvent]] = ???
  // format: on
  override def fetch: UIO[PermissionsResource]                                                                                                     =
    IO.pure(expected)
}
