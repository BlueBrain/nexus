package ch.epfl.bluebrain.nexus.admin.config
import ch.epfl.bluebrain.nexus.iam.client.types.Permission

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
}
