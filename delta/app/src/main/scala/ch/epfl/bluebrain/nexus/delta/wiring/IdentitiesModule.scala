package ch.epfl.bluebrain.nexus.delta.wiring

import cats.effect.unsafe.IORuntime
import cats.effect.{Clock, IO}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.kernel.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.IdentitiesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, OpenIdAuthService}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.identities.{Identities, IdentitiesImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import izumi.distage.model.definition.{Id, ModuleDef}

/**
  * Identities module wiring config.
  */
// $COVERAGE-OFF$
object IdentitiesModule extends ModuleDef {

  make[CacheConfig].from((cfg: AppConfig) => cfg.identities)

  make[Identities].fromEffect { (realms: Realms, hc: HttpClient @Id("realm"), config: CacheConfig) =>
    IdentitiesImpl(realms, hc, config)
  }

  make[OpenIdAuthService].from { (httpClient: HttpClient @Id("realm"), realms: Realms) =>
    new OpenIdAuthService(httpClient, realms)
  }

  make[AuthTokenProvider].fromEffect { (authService: OpenIdAuthService, clock: Clock[IO]) =>
    AuthTokenProvider(authService)(clock)
  }

  many[RemoteContextResolution].addEffect(ContextValue.fromFile("contexts/identities.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.identities -> ctx)
  })

  make[IdentitiesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering,
        runtime: IORuntime
    ) => new IdentitiesRoutes(identities, aclCheck)(baseUri, cr, ordering, runtime)

  }

  many[PriorityRoute].add { (route: IdentitiesRoutes) =>
    PriorityRoute(pluginsMaxPriority + 2, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
