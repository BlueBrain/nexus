package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.{Permission, PermissionsState}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject

import java.time.Instant

object PermissionsGen {

  import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions._

  /**
    * The collection of minimum permissions.
    */
  val minimum = Set(
    acls.read,
    acls.write,
    permissions.read,
    permissions.write,
    realms.read,
    realms.write,
    events.read,
    orgs.read,
    orgs.write,
    orgs.create,
    projects.read,
    projects.write,
    projects.create,
    resources.read,
    resources.write,
    resolvers.write,
    schemas.write
  )

  /**
    * The owner permissions to apply when creating an org/project
    */
  val ownerPermissions = Set(
    projects.read,
    resources.read,
    resources.write,
    resolvers.write,
    schemas.write
  )

  def resourceFor(
      permissions: Set[Permission],
      rev: Int,
      createdBy: Subject = Identity.Anonymous,
      updatedBy: Subject = Identity.Anonymous
  ): PermissionsResource =
    PermissionsState(rev, permissions, Instant.EPOCH, createdBy, Instant.EPOCH, updatedBy).toResource(permissions)

}
