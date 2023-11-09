package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.{Clock, ContextShift, IO, Timer}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, MetadataContextValue}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.realms.{Realms, RealmsImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import izumi.distage.model.definition.{Id, ModuleDef}

import scala.concurrent.ExecutionContext

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Realms].from {
    (
        cfg: AppConfig,
        clock: Clock[IO],
        timer: Timer[IO],
        hc: HttpClient @Id("realm"),
        xas: Transactors
    ) =>
      val wellKnownResolver = realms.WellKnownResolver((uri: Uri) => hc.toJson(HttpRequest(uri = uri))) _
      RealmsImpl(cfg.realms, wellKnownResolver, xas)(clock, timer)
  }

  make[RealmsRoutes].from {
    (
        identities: Identities,
        realms: Realms,
        cfg: AppConfig,
        aclCheck: AclCheck,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new RealmsRoutes(identities, realms, aclCheck)(cfg.http.baseUri, cfg.realms.pagination, cr, ordering)
  }

  make[HttpClient].named("realm").from {
    (as: ActorSystem[Nothing], ec: ExecutionContext, timer: Timer[IO], cs: ContextShift[IO]) =>
      HttpClient.noRetry(compression = false)(as.classicSystem, ec, timer, cs)
  }

  many[SseEncoder[_]].add { base: BaseUri => RealmEvent.sseEncoder(base) }

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

}
// $COVERAGE-ON$
