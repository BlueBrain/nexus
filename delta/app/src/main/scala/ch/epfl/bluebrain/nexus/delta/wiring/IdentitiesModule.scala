package ch.epfl.bluebrain.nexus.delta.wiring

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.config.{AppConfig, IdentitiesConfig}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.routes.IdentitiesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities, Realms}
import ch.epfl.bluebrain.nexus.delta.service.identity.{GroupsConfig, IdentitiesImpl}
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

  make[IdentitiesConfig].from((cfg: AppConfig) => cfg.identities)
  make[GroupsConfig].from((cfg: IdentitiesConfig) => cfg.groups)

  make[Identities].fromEffect {
    (realms: Realms, hc: HttpClient @Id("realm"), gc: GroupsConfig, as: ActorSystem[Nothing]) =>
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
      IdentitiesImpl(findActiveRealm, getUserInfo, gc)(as)
  }

  many[RemoteContextResolution].addEffect(ioJsonContentOf("contexts/identities.json").memoizeOnSuccess.map { ctx =>
    RemoteContextResolution.fixed(contexts.acls -> ctx)
  })

  make[IdentitiesRoutes].from {
    (
        identities: Identities,
        acls: Acls,
        s: Scheduler,
        baseUri: BaseUri,
        cr: RemoteContextResolution @Id("aggregate"),
        ordering: JsonKeyOrdering
    ) => new IdentitiesRoutes(identities, acls)(s, baseUri, cr, ordering)

  }

}
// $COVERAGE-ON$
