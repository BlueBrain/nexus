package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.routes.marshalling.CirceUnmarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Envelope}
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Realms}
import ch.epfl.bluebrain.nexus.delta.service.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.service.realms.{RealmsConfig, RealmsImpl, WellKnownResolver}
import ch.epfl.bluebrain.nexus.sourcing.EventLog
import io.circe.Json
import izumi.distage.model.definition.ModuleDef
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {

  make[RealmsConfig].from((cfg: AppConfig) => cfg.realms)

  make[EventLog[Envelope[RealmEvent]]].fromEffect { databaseEventLog[RealmEvent](_, _) }

  make[Realms].fromEffect {
    (
        cfg: RealmsConfig,
        eventLog: EventLog[Envelope[RealmEvent]],
        as: ActorSystem[Nothing],
        scheduler: Scheduler,
        hc: HttpClient
    ) =>
      val wellKnownResolver = WellKnownResolver((uri: Uri) => hc[Json](HttpRequest(uri = uri))) _
      RealmsImpl(cfg, wellKnownResolver, eventLog)(as, scheduler, Clock[UIO])
  }

  make[RealmsRoutes].from {
    (
        identities: Identities,
        realms: Realms,
        baseUri: BaseUri,
        cfg: RealmsConfig,
        s: Scheduler,
        cr: RemoteContextResolution,
        ordering: JsonKeyOrdering
    ) =>
      new RealmsRoutes(identities, realms)(baseUri, cfg.pagination, s, cr, ordering)
  }

}
// $COVERAGE-ON$
