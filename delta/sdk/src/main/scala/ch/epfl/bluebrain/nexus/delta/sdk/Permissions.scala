package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsRejection.RevisionNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionsRejection}
import monix.bio.{IO, UIO}

/**
  * Operations pertaining to managing permissions.
  */
trait Permissions {

  /**
    * @return the permissions singleton persistence id
    */
  def persistenceId: String

  /**
    * @return the minimum set of permissions
    */
  def minimum: Set[Permission]

  /**
    * @return the current permissions as a resource
    */
  def fetch: UIO[PermissionsResource]

  /**
    * @param rev the permissions revision
    * @return the permissions as a resource at the specified revision
    */
  def fetchAt(rev: Long): IO[RevisionNotFound, PermissionsResource]

  /**
    * @return the current permissions collection without checking permissions
    */
  def fetchPermissionSet: UIO[Set[Permission]] =
    fetch.map(_.value)

  /**
    * Replaces the current collection of permissions with the provided collection.
    *
    * @param permissions the permissions to set
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def replace(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Appends the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to append
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def append(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Subtracts the provided permissions to the current collection of permissions.
    *
    * @param permissions the permissions to subtract
    * @param rev         the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def subtract(permissions: Set[Permission], rev: Long): IO[PermissionsRejection, PermissionsResource]

  /**
    * Removes all but the minimum permissions from the collection of permissions.
    *
    * @param rev the last known revision of the resource
    * @return the new resource or a description of why the change was rejected
    */
  def delete(rev: Long): IO[PermissionsRejection, PermissionsResource]
}
