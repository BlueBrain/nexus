package ch.epfl.bluebrain.nexus.delta.sdk.identities

import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.data.NonEmptySet
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.kamon.KamonMetricComponent
import ch.epfl.bluebrain.nexus.delta.sdk.cache.{CacheConfig, KeyValueStore}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesImpl.{extractGroups, logger, GroupsCache}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.TokenRejection.{GetGroupsFromOidcError, InvalidAccessToken, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, User}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
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

    def validate(audiences: Option[NonEmptySet[String]], token: ParsedToken, keySet: JWKSet) = {
      val proc        = new DefaultJWTProcessor[SecurityContext]
      val keySelector = new JWSVerificationKeySelector(JWSAlgorithm.RS256, new ImmutableJWKSet[SecurityContext](keySet))
      proc.setJWSKeySelector(keySelector)
      audiences.foreach { aud =>
        proc.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier(aud.toSet.asJava, null, null, null))
      }
      IO.fromEither(
        Either
          .catchNonFatal(proc.process(token.jwtToken, null))
          .leftMap(err => InvalidAccessToken(token.subject, token.issuer, err.getMessage))
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
              extractGroups(getUserInfo)(parsedToken, realm).map(_.getOrElse(Set.empty))
            )
            .span("fetchGroups")
        }
    }

    val result = for {
      parsedToken       <- IO.fromEither(ParsedToken.fromToken(token))
      activeRealmOption <- findActiveRealm(parsedToken.issuer)
      activeRealm       <- IO.fromOption(activeRealmOption, UnknownAccessTokenIssuer)
      _                 <- validate(activeRealm.acceptedAudiences, parsedToken, realmKeyset(activeRealm))
      groups            <- fetchGroups(parsedToken, activeRealm)
    } yield {
      val user = User(parsedToken.subject, activeRealm.label)
      Caller(user, groups ++ Set(Anonymous, user, Authenticated(activeRealm.label)))
    }
    result.span("exchangeToken")
  }.tapError { rejection =>
    UIO.delay(logger.error(s"Extracting and validating the caller failed for the reason: $rejection"))
  }
}

object IdentitiesImpl {

  type GroupsCache = KeyValueStore[String, Set[Group]]

  private val logger: Logger = Logger[this.type]

  def extractGroups(
      getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json]
  )(token: ParsedToken, realm: Realm): IO[TokenRejection, Option[Set[Group]]] = {
    def fromSet(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[Set[String]]("groups").map(_.map(_.trim).filterNot(_.isEmpty))
    def fromCsv(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[String]("groups").map(_.split(",").map(_.trim).filterNot(_.isEmpty).toSet)
    getUserInfo(realm.userInfoEndpoint, OAuth2BearerToken(token.rawToken))
      .map { json =>
        val stringGroups = fromSet(json.hcursor) orElse fromCsv(json.hcursor) getOrElse Set.empty[String]
        Some(stringGroups.map(str => Group(str, realm.label)))
      }
      .onErrorHandleWith {
        case e: HttpClientStatusError if e.code == StatusCodes.Unauthorized || e.code == StatusCodes.Forbidden =>
          val message =
            s"A provided client token was rejected by the OIDC provider for user '${token.subject}' of realm '${token.issuer}', reason: '${e.reason}'"
          UIO.delay(logger.error(message, e)) >>
            IO.raiseError(InvalidAccessToken(token.subject, token.issuer, e.getMessage))
        case e                                                                                                 =>
          val message =
            s"A call to get the groups from the OIDC provider failed unexpectedly for user '${token.subject}' of realm '${token.issuer}'."
          UIO.delay(logger.error(message, e)) >> IO.raiseError(GetGroupsFromOidcError(token.subject, token.issuer))
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
      config: CacheConfig
  ): UIO[Identities] =
    KeyValueStore.localLRU(config).map { groups =>
      new IdentitiesImpl(findActiveRealm, getUserInfo, groups)
    }
}
