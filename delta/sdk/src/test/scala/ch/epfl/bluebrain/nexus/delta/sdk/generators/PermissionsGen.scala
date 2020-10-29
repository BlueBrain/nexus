package ch.epfl.bluebrain.nexus.delta.sdk.generators

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

}
