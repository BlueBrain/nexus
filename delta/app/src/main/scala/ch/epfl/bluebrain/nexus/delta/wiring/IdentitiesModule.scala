package ch.epfl.bluebrain.nexus.delta.wiring

import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.Main.pluginsMaxPriority
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.IdentitiesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.PriorityRoute
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclCheck
import ch.epfl.bluebrain.nexus.delta.sdk.cache.CacheConfig
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.{Identities, IdentitiesImpl}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import io.circe.Json
import izumi.distage.model.definition.{Id, ModuleDef}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

/**
  * Identities module wiring config.
  */
// $COVERAGE-OFF$
object IdentitiesModule extends ModuleDef {
  implicit private val classLoader = getClass.getClassLoader

  make[CacheConfig].from((cfg: AppConfig) => cfg.identities)

  make[Identities].fromEffect { (realms: Realms, hc: HttpClient @Id("realm"), config: CacheConfig) =>
    val findActiveRealm: String => UIO[Option[Realm]] = { (issuer: String) =>
      realms
        .list(
          FromPagination(0, 1000),
          RealmSearchParams(
            issuer = Some(issuer),
            deprecated = Some(false)
          ),
          ResourceF.defaultSort[Realm]
        )
        .map { results =>
          results.results.map(entry => entry.source.value).headOption
        }
    }
    val getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json] = { (uri: Uri, token: OAuth2BearerToken) =>
      hc.toJson(HttpRequest(uri = uri, headers = List(Authorization(token))))
    }
    IdentitiesImpl(findActiveRealm, getUserInfo, config)
  }

  many[RemoteContextResolution].addEffect(ContextValue.fromFile("contexts/identities.json").map { ctx =>
    RemoteContextResolution.fixed(contexts.identities -> ctx)
  })

  make[IdentitiesRoutes].from {
    (
        identities: Identities,
        aclCheck: AclCheck,
        s: Scheduler,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new IdentitiesRoutes(identities, aclCheck)(s, baseUri, cr, ordering)

  }

  many[PriorityRoute].add { (route: IdentitiesRoutes) =>
    PriorityRoute(pluginsMaxPriority + 2, route.routes, requiresStrictEntity = true)
  }

}
// $COVERAGE-ON$
