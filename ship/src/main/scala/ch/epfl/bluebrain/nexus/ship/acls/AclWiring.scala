package ch.epfl.bluebrain.nexus.ship.acls

import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.{Acls, AclsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.EventLogConfig

object AclWiring {

  def acls(config: EventLogConfig, clock: Clock[IO], xas: Transactors): Acls = {
    val permissionSet = Set(Permission.unsafe("resources/read"))
    AclsImpl(
      IO.pure(permissionSet),
      AclsImpl.findUnknownRealms(xas),
      permissionSet,
      config,
      xas,
      clock
    )
  }

}
