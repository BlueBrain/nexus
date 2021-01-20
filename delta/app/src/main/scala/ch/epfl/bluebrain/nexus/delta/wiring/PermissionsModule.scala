package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.routes.PermissionsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.model.Envelope
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.service.permissions.PermissionsImpl
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO

/**
  * Permissions module wiring config.
  */
// $COVERAGE-OFF$
object PermissionsModule extends ModuleDef {

  make[EventLog[Envelope[PermissionsEvent]]].fromEffect { databaseEventLog[PermissionsEvent](_, _) }

  make[Permissions].fromEffect {
    (cfg: AppConfig, log: EventLog[Envelope[PermissionsEvent]], as: ActorSystem[Nothing]) =>
      PermissionsImpl(
        cfg.permissions.minimum,
        cfg.permissions.aggregate,
        log
      )(as, Clock[UIO])
  }

  make[PermissionsRoutes]

}
// $COVERAGE-ON$
