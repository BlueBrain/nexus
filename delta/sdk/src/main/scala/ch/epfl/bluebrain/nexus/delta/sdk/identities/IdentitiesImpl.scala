package ch.epfl.bluebrain.nexus.delta.sdk.identities

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import IdentitiesImpl.{extractGroups, GroupsCache}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection.{GetGroupsFromOidcError, InvalidAccessToken, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.{DefaultJWTClaimsVerifier, DefaultJWTProcessor}
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, HCursor, Json}
import monix.bio.{IO, UIO}

import scala.util.Try

class IdentitiesImpl private (
    findActiveRealm: String => UIO[Option[Realm]],
    getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json],
    groups: GroupsCache
) extends Identities {
  import scala.jdk.CollectionConverters._

  implicit private val kamonComponent: KamonMetricComponent = KamonMetricComponent("identities")

  override def exchange(token: AuthToken): IO[TokenRejection, Caller] = {
    def realmKeyset(realm: Realm) = {
      val jwks = realm.keys.foldLeft(Set.empty[JWK]) { case (acc, e) =>
        Try(JWK.parse(e.noSpaces)).map(acc + _).getOrElse(acc)
      }
      new JWKSet(jwks.toList.asJava)
    }

    def validate(audiences: Option[NonEmptySet[String]], jwt: SignedJWT, keySet: JWKSet) = {
      val proc        = new DefaultJWTProcessor[SecurityContext]
      val keySelector = new JWSVerificationKeySelector(JWSAlgorithm.RS256, new ImmutableJWKSet[SecurityContext](keySet))
      proc.setJWSKeySelector(keySelector)
      audiences.foreach { aud =>
        proc.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier(aud.value.asJava, null, null, null))
      }
      IO.fromEither(
        Either
          .catchNonFatal(proc.process(jwt, null))
          .leftMap(err => InvalidAccessToken(Option(err.getMessage).filter(_.trim.nonEmpty)))
      )
    }

    def fetchGroups(parsedToken: ParsedToken, realm: Realm): IO[TokenRejection, Set[Group]] = {
      parsedToken.groups
        .map { s =>
          IO.pure(s.map(Group(_, realm.label)))
        }
        .getOrElse {
          groups
            .getOrElseUpdate(
              parsedToken.rawToken,
              extractGroups(getUserInfo)(parsedToken.rawToken, realm).map(_.getOrElse(Set.empty))
            )
            .span("fetchGroups")
        }
    }

    val result = for {
      parsedToken       <- IO.fromEither(ParsedToken.fromToken(token))
      activeRealmOption <- findActiveRealm(parsedToken.issuer)
      activeRealm       <- IO.fromOption(activeRealmOption, UnknownAccessTokenIssuer)
      _                 <- validate(activeRealm.acceptedAudiences, parsedToken.jwtToken, realmKeyset(activeRealm))
      groups            <- fetchGroups(parsedToken, activeRealm)
    } yield {
      val user = User(parsedToken.subject, activeRealm.label)
      Caller(user, groups ++ Set(Anonymous, user, Authenticated(activeRealm.label)))
    }
    result.span("exchangeToken")
  }
}

object IdentitiesImpl {

  type GroupsCache = KeyValueStore[String, Set[Group]]

  val logger: Logger = Logger[this.type]

  def extractGroups(
      getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json]
  )(token: String, realm: Realm): IO[TokenRejection, Option[Set[Group]]] = {
    def fromSet(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[Set[String]]("groups").map(_.map(_.trim).filterNot(_.isEmpty))
    def fromCsv(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[String]("groups").map(_.split(",").map(_.trim).filterNot(_.isEmpty).toSet)
    getUserInfo(realm.userInfoEndpoint, OAuth2BearerToken(token))
      .map { json =>
        val stringGroups = fromSet(json.hcursor) orElse fromCsv(json.hcursor) getOrElse Set.empty[String]
        Some(stringGroups.map(str => Group(str, realm.label)))
      }
      .mapError {
        case HttpClientStatusError(_, code, message)
            if code == StatusCodes.Unauthorized || code == StatusCodes.Forbidden =>
          logger.warn(s"A provided client token was rejected by the OIDC provider, reason: '$message'")
          InvalidAccessToken(Option.when(message.trim.nonEmpty)(message))
        case e =>
          logger.warn(s"A call to get the groups from the OIDC provider failed unexpectedly, reason: '${e.asString}'")
          GetGroupsFromOidcError
      }
  }

  /**
    * Constructs a [[IdentitiesImpl]] instance
    * @param findActiveRealm
    *   function to find the active realm matching the given issuer
    * @param getUserInfo
    *   function to retrieve user info from the OIDC provider
    * @param config
    *   the indentities configuration
    */
  def apply(
      findActiveRealm: String => UIO[Option[Realm]],
      getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json],
      config: IdentitiesConfig
  ): UIO[Identities] =
    KeyValueStore.localLRU(config.cacheMaxSize.toLong, config.cacheExpiration).map { groups =>
      new IdentitiesImpl(findActiveRealm, getUserInfo, groups)
    }
}
