package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, IdentitiesConfig}
import ch.epfl.bluebrain.nexus.delta.routes.IdentitiesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.{Identities, Realms}
import ch.epfl.bluebrain.nexus.delta.service.identity.{GroupsConfig, IdentitiesImpl}
import io.circe.Json
import izumi.distage.model.definition.ModuleDef
import monix.bio.{IO, UIO}

/**
  * Identities module wiring config.
  */
// $COVERAGE-OFF$
object IdentitiesModule extends ModuleDef {

  make[IdentitiesConfig].from((cfg: AppConfig) => cfg.identities)
  make[GroupsConfig].from((cfg: IdentitiesConfig) => cfg.groups)

  make[Identities].fromEffect { (realms: Realms, hc: HttpClient, gc: GroupsConfig, as: ActorSystem[Nothing]) =>
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
      hc.to[Json](HttpRequest(uri = uri, headers = List(Authorization(token))))
    }
    IdentitiesImpl(findActiveRealm, getUserInfo, gc)(as)
  }

  make[IdentitiesRoutes]

}
// $COVERAGE-ON$
