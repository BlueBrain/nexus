package ch.epfl.bluebrain.nexus.delta.sdk.generators

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.sdk.PermissionsResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.{Permission, PermissionSet}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{AccessUrl, BaseUri, ResourceF}

object PermissionsGen {

  import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{schemas, _}

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
    views.write,
    views.query,
    schemas.write,
    files.write,
    storages.write,
    archives.write
  )

  def resourceFor(
      permissions: Set[Permission],
      rev: Long,
      createdBy: Subject = Identity.Anonymous,
      updatedBy: Subject = Identity.Anonymous,
      deprecated: Boolean = false
  )(implicit base: BaseUri): PermissionsResource = {
    val accessUrl = AccessUrl.permissions
    ResourceF(
      id = accessUrl.iri,
      accessUrl = accessUrl,
      rev = rev,
      types = Set(nxv.Permissions),
      deprecated = deprecated,
      createdAt = Instant.EPOCH,
      createdBy = createdBy,
      updatedAt = Instant.EPOCH,
      updatedBy = updatedBy,
      schema = Latest(Vocabulary.schemas.permissions),
      value = PermissionSet(permissions)
    )
  }

}
