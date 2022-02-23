package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.eventlog.EventLogUtils.databaseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.service.realms.{RealmEventExchange, RealmsImpl, WellKnownResolver}
import ch.epfl.bluebrain.nexus.delta.sourcing.EventLog
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[EventLog[Envelope[RealmEvent]]].fromEffect { databaseEventLog[RealmEvent](_, _) }

  make[Realms].fromEffect {
    (
        cfg: AppConfig,
        eventLog: EventLog[Envelope[RealmEvent]],
        as: ActorSystem[Nothing],
        uuidF: UUIDF,
        clock: Clock[UIO],
        scheduler: Scheduler,
        hc: HttpClient @Id("realm")
    ) =>
      val wellKnownResolver = WellKnownResolver((uri: Uri) => hc.toJson(HttpRequest(uri = uri))) _
      RealmsImpl(cfg.realms, wellKnownResolver, eventLog)(uuidF, as, scheduler, clock)
  }

  make[RealmsRoutes].from {
    (
        identities: Identities,
        realms: Realms,
        cfg: AppConfig,
        acls: Acls,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new RealmsRoutes(identities, realms, acls)(cfg.http.baseUri, cfg.realms.pagination, s, cr, ordering)
  }

  make[HttpClient].named("realm").from { (cfg: AppConfig, as: ActorSystem[Nothing], sc: Scheduler) =>
    HttpClient()(cfg.realms.client, as.classicSystem, sc)
  }

  many[MetadataContextValue].addEffect(MetadataContextValue.fromFile("contexts/realms-metadata.json"))

  many[RemoteContextResolution].addEffect(
    for {
      realmCtx      <- ContextValue.fromFile("contexts/realms.json")
      realmsMetaCtx <- ContextValue.fromFile("contexts/realms-metadata.json")
    } yield RemoteContextResolution.fixed(contexts.realms -> realmCtx, contexts.realmsMetadata -> realmsMetaCtx)
  )

  many[PriorityRoute].add { (route: RealmsRoutes) =>
    PriorityRoute(pluginsMaxPriority + 4, route.routes, requiresStrictEntity = true)
  }

  make[RealmEventExchange]
  many[EventExchange].ref[RealmEventExchange]
}
// $COVERAGE-ON$
