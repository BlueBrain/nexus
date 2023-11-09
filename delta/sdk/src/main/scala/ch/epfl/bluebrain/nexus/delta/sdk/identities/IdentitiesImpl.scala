package ch.epfl.bluebrain.nexus.delta.sdk.identities

import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpRequest, StatusCodes, Uri}
import cats.data.OptionT
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.cache.{CacheConfig, LocalCache}
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.TokenRejection.{GetGroupsFromOidcError, InvalidAccessToken, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.kernel.jwt.{AuthToken, ParsedToken}
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.FromPagination
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesImpl.{extractGroups, logger, GroupsCache, RealmCache}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams.RealmSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import io.circe.{Decoder, HCursor, Json}

import scala.util.Try

class IdentitiesImpl private[identities] (
    realm: RealmCache,
    findActiveRealm: String => IO[Option[Realm]],
    getUserInfo: (Uri, OAuth2BearerToken) => IO[Json],
    groups: GroupsCache
) extends Identities {
  import scala.jdk.CollectionConverters._

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent("identities")

  override def exchange(token: AuthToken): IO[Caller] = {
    def realmKeyset(realm: Realm) = {
      val jwks = realm.keys.foldLeft(Set.empty[JWK]) { case (acc, e) =>
        Try(JWK.parse(e.noSpaces)).map(acc + _).getOrElse(acc)
      }
      new JWKSet(jwks.toList.asJava)
    }

    def fetchRealm(parsedToken: ParsedToken): IO[Realm] = {
      val getRealm = realm.getOrElseAttemptUpdate(parsedToken.issuer, findActiveRealm(parsedToken.issuer))
      OptionT(getRealm).getOrRaise(UnknownAccessTokenIssuer)
    }

    def fetchGroups(parsedToken: ParsedToken, realm: Realm): IO[Set[Group]] = {
      parsedToken.groups
        .map { s =>
          IO.pure(s.map(Group(_, realm.label)))
        }
        .getOrElse {
          groups
            .getOrElseUpdate(
              parsedToken.rawToken,
              extractGroups(getUserInfo)(parsedToken, realm).map(_.getOrElse(Set.empty))
            )
            .span("fetchGroups")
        }
    }

    val result = for {
      parsedToken <- IO.fromEither(ParsedToken.fromToken(token))
      activeRealm <- fetchRealm(parsedToken)
      _           <- IO.fromEither(parsedToken.validate(activeRealm.acceptedAudiences, realmKeyset(activeRealm)))
      groups      <- fetchGroups(parsedToken, activeRealm)
    } yield {
      val user = User(parsedToken.subject, activeRealm.label)
      Caller(user, groups ++ Set(Anonymous, user, Authenticated(activeRealm.label)))
    }
    result.span("exchangeToken")
  }.onError { rejection =>
    logger.debug(s"Extracting and validating the caller failed for the reason: $rejection")
  }
}

object IdentitiesImpl {

  type GroupsCache = LocalCache[String, Set[Group]]
  type RealmCache  = LocalCache[String, Realm]

  private val logger = Logger[this.type]

  def extractGroups(
      getUserInfo: (Uri, OAuth2BearerToken) => IO[Json]
  )(token: ParsedToken, realm: Realm): IO[Option[Set[Group]]] = {
    def fromSet(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[Set[String]]("groups").map(_.map(_.trim).filterNot(_.isEmpty))
    def fromCsv(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[String]("groups").map(_.split(",").map(_.trim).filterNot(_.isEmpty).toSet)
    getUserInfo(realm.userInfoEndpoint, OAuth2BearerToken(token.rawToken))
      .map { json =>
        val stringGroups = fromSet(json.hcursor) orElse fromCsv(json.hcursor) getOrElse Set.empty[String]
        Some(stringGroups.map(str => Group(str, realm.label)))
      }
      .handleErrorWith {
        case e: HttpClientStatusError if e.code == StatusCodes.Unauthorized || e.code == StatusCodes.Forbidden =>
          val message =
            s"A provided client token was rejected by the OIDC provider for user '${token.subject}' of realm '${token.issuer}', reason: '${e.reason}'"
          logger.debug(e)(message) >> IO.raiseError(InvalidAccessToken(token.subject, token.issuer, e.getMessage))
        case e                                                                                                 =>
          val message =
            s"A call to get the groups from the OIDC provider failed unexpectedly for user '${token.subject}' of realm '${token.issuer}'."
          logger.error(e)(message) >> IO.raiseError(GetGroupsFromOidcError(token.subject, token.issuer))
      }
  }

  /**
    * Constructs a [[IdentitiesImpl]] instance
    *
    * @param realms
    *   the realms instance
    * @param hc
    *   the http client to retrieve groups
    * @param config
    *   the cache configuration
    */
  def apply(realms: Realms, hc: HttpClient, config: CacheConfig): IO[Identities] = {
    val groupsCache = LocalCache[String, Set[Group]](config)
    val realmCache  = LocalCache[String, Realm](config)

    val findActiveRealm: String => IO[Option[Realm]] = { (issuer: String) =>
      val pagination = FromPagination(0, 1000)
      val params     = RealmSearchParams(issuer = Some(issuer), deprecated = Some(false))
      val sort       = ResourceF.defaultSort[Realm]

      realms.list(pagination, params, sort).map {
        _.results.map(entry => entry.source.value).headOption
      }
    }
    val getUserInfo: (Uri, OAuth2BearerToken) => IO[Json] = { (uri: Uri, token: OAuth2BearerToken) =>
      hc.toJson(HttpRequest(uri = uri, headers = List(Authorization(token))))
    }

    (realmCache, groupsCache).mapN { (realm, groups) =>
      new IdentitiesImpl(realm, findActiveRealm, getUserInfo, groups)
    }
  }

}
