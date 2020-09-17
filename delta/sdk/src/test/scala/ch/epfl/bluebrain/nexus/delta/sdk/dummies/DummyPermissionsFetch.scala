package ch.epfl.bluebrain.nexus.delta.sdk.dummies

import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.{Permissions, PermissionsResource}
import monix.bio.{IO, UIO}

/**
  * Partial dummy implementation, that only implements the fetch of all the permissions
  *
 * @param expected the expected result of fetching all the permissions
  */
class DummyPermissionsFetch(expected: PermissionsResource) extends Permissions {
  override def persistenceId: String                                                                            = ???
  override def minimum: Set[Permission]                                                                         = ???
  override def fetch: UIO[PermissionsResource]                                                                  = IO.pure(expected)
  override def fetchAt(rev: Long): IO[PermissionsRejection.RevisionNotFound, PermissionsResource]               = ???
  override def replace(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]  = ???
  override def append(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]   = ???
  override def subtract(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource] = ???
  override def delete(rev: Long): IO[PermissionsRejection, PermissionsResource]                                 = ???
}
