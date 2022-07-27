package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.Clock
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.RealmsRoutes
import ch.epfl.bluebrain.nexus.delta.sdk._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.model.MetadataContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.realms.{Realms, RealmsImpl}
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.UIO
import monix.execution.Scheduler

/**
  * Realms module wiring config.
  */
// $COVERAGE-OFF$
object RealmsModule extends ModuleDef {
  implicit private val classLoader: ClassLoader = getClass.getClassLoader

  make[Realms].from {
    (
        cfg: AppConfig,
        clock: Clock[UIO],
        hc: HttpClient @Id("realm"),
        xas: Transactors
    ) =>
      val wellKnownResolver = realms.WellKnownResolver((uri: Uri) => hc.toJson(HttpRequest(uri = uri))) _
      RealmsImpl(cfg.realms, wellKnownResolver, xas)(clock)
  }

  make[RealmsRoutes].from {
    (
        identities: Identities,
        realms: Realms,
        cfg: AppConfig,
        aclCheck: AclCheck,
        s: Scheduler,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) =>
      new RealmsRoutes(identities, realms, aclCheck)(cfg.http.baseUri, cfg.realms.pagination, s, cr, ordering)
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

}
// $COVERAGE-ON$
