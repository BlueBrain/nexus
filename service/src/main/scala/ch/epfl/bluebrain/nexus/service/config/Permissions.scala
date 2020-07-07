package ch.epfl.bluebrain.nexus.service.config

import ch.epfl.bluebrain.nexus.iam.types.Permission

/**
  * Constant enumeration of permissions.
  */
object Permissions {

  /**
    * Organization permissions.
    */
  object orgs {
    final val read: Permission   = Permission.unsafe("organizations/read")
    final val write: Permission  = Permission.unsafe("organizations/write")
    final val create: Permission = Permission.unsafe("organizations/create")
  }

  /**
    * Project permissions.
    */
  object projects {
    final val read: Permission   = Permission.unsafe("projects/read")
    final val write: Permission  = Permission.unsafe("projects/write")
    final val create: Permission = Permission.unsafe("projects/create")
  }

  /**
    * Generic event permissions.
    */
  object events {
    final val read: Permission = Permission.unsafe("events/read")
  }

  object resources {
    final val read: Permission  = Permission.unsafe("resources/read")
    final val write: Permission = Permission.unsafe("resources/write")
  }

  object schemas {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("schemas/write")
  }

  object views {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("views/write")
    final val query: Permission = Permission.unsafe("views/query")
  }

  object files {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("files/write")
  }

  object storages {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("storages/write")
  }

  object resolvers {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("resolvers/write")
  }

  object archives {
    final val read: Permission  = resources.read
    final val write: Permission = Permission.unsafe("archives/write")
  }

  object acls {
    final val read: Permission  = Permission.unsafe("acls/read")
    final val write: Permission = Permission.unsafe("acls/write")
  }

  object realms {
    final val read: Permission  = Permission.unsafe("realms/read")
    final val write: Permission = Permission.unsafe("realms/write")
  }

  object permissions {
    final val read: Permission  = Permission.unsafe("realms/read")
    final val write: Permission = Permission.unsafe("realms/write")
  }
}
