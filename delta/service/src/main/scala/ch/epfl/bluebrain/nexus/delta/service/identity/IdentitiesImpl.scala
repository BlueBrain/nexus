package ch.epfl.bluebrain.nexus.delta.service.identity

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.{GetGroupsFromOidcError, InvalidAccessToken, UnknownAccessTokenIssuer}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.service.identity.IdentitiesImpl.{FetchGroups, GroupsCache}
import ch.epfl.bluebrain.nexus.sourcing.processor.ShardedAggregate
import ch.epfl.bluebrain.nexus.sourcing.{Aggregate, TransientEventDefinition}
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.{JWK, JWKSet}
import com.nimbusds.jose.proc.{JWSVerificationKeySelector, SecurityContext}
import com.nimbusds.jwt.SignedJWT
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, HCursor, Json}
import monix.bio.{IO, UIO}

import scala.util.Try

class IdentitiesImpl private (findActiveRealm: String => UIO[Option[Realm]], groups: GroupsCache) extends Identities {

  private val component: String = "identities"

  override def exchange(token: AuthToken): IO[TokenRejection, Caller] = {
    def realmKeyset(realm: Realm) = {
      val jwks = realm.keys.foldLeft(Set.empty[JWK]) { case (acc, e) =>
        Try(JWK.parse(e.noSpaces)).map(acc + _).getOrElse(acc)
      }
      import scala.jdk.CollectionConverters._
      new JWKSet(jwks.toList.asJava)
    }

    def validate(jwt: SignedJWT, keySet: JWKSet) = {
      val proc        = new DefaultJWTProcessor[SecurityContext]
      val keySelector = new JWSVerificationKeySelector(JWSAlgorithm.RS256, new ImmutableJWKSet[SecurityContext](keySet))
      proc.setJWSKeySelector(keySelector)
      IO.fromEither(
        Either
          .catchNonFatal(proc.process(jwt, null))
          .leftMap(_ => InvalidAccessToken)
      )
    }

    def fetchGroups(parsedToken: ParsedToken, realm: Realm): IO[TokenRejection, Set[Group]] = {
      parsedToken.groups
        .map { s =>
          IO.pure(s.map(Group(_, realm.label)))
        }
        .getOrElse {
          groups
            .state(parsedToken.rawToken)
            .flatMap {
              case Some(set) => IO.pure(set)
              case None      =>
                groups
                  .evaluate(parsedToken.rawToken, FetchGroups(parsedToken.rawToken, realm))
                  .map { r =>
                    r.state.getOrElse(Set.empty)
                  }
                  .mapError(_.value)
            }
            .named("fetchGroups", component)
        }
    }

    val result = for {
      parsedToken       <- IO.fromEither(ParsedToken.fromToken(token))
      activeRealmOption <- findActiveRealm(parsedToken.issuer)
      activeRealm       <- IO.fromOption(activeRealmOption, UnknownAccessTokenIssuer)
      _                 <- validate(parsedToken.jwtToken, realmKeyset(activeRealm))
      groups            <- fetchGroups(parsedToken, activeRealm)
    } yield {
      val user = User(parsedToken.subject, activeRealm.label)
      Caller(user, groups ++ Set(Anonymous, user, Authenticated(activeRealm.label)))
    }
    result.named("exchangeToken", component)
  }
}

object IdentitiesImpl {

  type GroupsCache = Aggregate[String, Option[Set[Group]], FetchGroups, Option[Set[Group]], TokenRejection]

  val logger: Logger = Logger[this.type]

  /**
    * Unique command for the group aggregate to fetch groups from the OIDC provider
    * @param token the raw token
    * @param realm the realm containing the user endpoint to fetch stuff from
    */
  final case class FetchGroups(token: String, realm: Realm)

  private def evaluate(
      getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json]
  )(fetchGroups: FetchGroups): IO[TokenRejection, Option[Set[Group]]] = {
    def fromSet(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[Set[String]]("groups").map(_.map(_.trim).filterNot(_.isEmpty))
    def fromCsv(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[String]("groups").map(_.split(",").map(_.trim).filterNot(_.isEmpty).toSet)
    getUserInfo(fetchGroups.realm.userInfoEndpoint, OAuth2BearerToken(fetchGroups.token))
      .map { json =>
        val stringGroups = fromSet(json.hcursor) orElse fromCsv(json.hcursor) getOrElse Set.empty[String]
        Some(stringGroups.map(str => Group(str, fetchGroups.realm.label)))
      }
      .mapError {
        case HttpClientStatusError(_, code, message)
            if code == StatusCodes.Unauthorized || code == StatusCodes.Forbidden =>
          logger.warn(s"A provided client token was rejected by the OIDC provider, reason: '$message'")
          InvalidAccessToken
        case e =>
          logger.warn(s"A call to get the groups from the OIDC provider failed unexpectedly, reason: '${e.asString}'")
          GetGroupsFromOidcError
      }
  }

  private def groupAggregate(getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json], config: GroupsConfig)(
      implicit as: ActorSystem[Nothing]
  ): UIO[GroupsCache] = {
    val definition = TransientEventDefinition.cache(
      entityType = "groups",
      None,
      (_: Option[Set[Group]], f: FetchGroups) => evaluate(getUserInfo)(f),
      stopStrategy = config.aggregate.stopStrategy.transientStrategy
    )

    ShardedAggregate.transientSharded(
      definition,
      config.aggregate.processor,
      RetryStrategy.retryOnNonFatal(config.retryStrategy, logger)
      // TODO: configure the number of shards
    )
  }

  /**
    * Constructs a [[IdentitiesImpl]] instance
    * @param findActiveRealm function to find the active realm matching the given issuer
    * @param getUserInfo     function to retrieve user info from the OIDC provider
    * @param groupsConfig    the groups aggregate configuration
    * @param as              the actor system
    */
  def apply(
      findActiveRealm: String => UIO[Option[Realm]],
      getUserInfo: (Uri, OAuth2BearerToken) => IO[HttpClientError, Json],
      groupsConfig: GroupsConfig
  )(implicit as: ActorSystem[Nothing]): UIO[IdentitiesImpl] =
    groupAggregate(
      getUserInfo,
      groupsConfig
    ).map { agg =>
      new IdentitiesImpl(
        findActiveRealm,
        agg
      )
    }

}
