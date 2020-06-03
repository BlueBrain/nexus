package ch.epfl.bluebrain.nexus.iam.realms

import java.time.Instant

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import cats.Monad
import cats.effect.{Effect, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, UnexpectedUnsuccessfulHttpResponse}
import ch.epfl.bluebrain.nexus.iam.auth.{AccessToken, TokenRejection}
import ch.epfl.bluebrain.nexus.iam.instances._
import ch.epfl.bluebrain.nexus.iam.realms.GroupsCache.Write
import ch.epfl.bluebrain.nexus.iam.types.Identity.Group
import ch.epfl.bluebrain.nexus.iam.types.{IamError, Label}
import ch.epfl.bluebrain.nexus.sourcing.StateMachine
import ch.epfl.bluebrain.nexus.sourcing.akka.StopStrategy
import ch.epfl.bluebrain.nexus.sourcing.akka.statemachine.{AkkaStateMachine, StateMachineConfig => GroupsConfig}
import ch.epfl.bluebrain.nexus.sourcing.syntax._
import com.nimbusds.jwt.JWTClaimsSet
import com.typesafe.scalalogging.Logger
import io.circe.{Decoder, HCursor, Json}
import retry.CatsEffect._
import retry.RetryPolicy
import retry.syntax.all._

import scala.concurrent.duration._
import scala.util.Try

/**
  * Extracts and caches caller group set using the access token or the realm user info endpoint as sources.
  *
  * @param cache the groups cache
  */
class Groups[F[_]: Timer](cache: GroupsCache[F])(implicit cfg: GroupsConfig, hc: HttpClient[F, Json], F: Effect[F]) {

  private[this] val logger                    = Logger[this.type]
  implicit private val policy: RetryPolicy[F] = cfg.retry.retryPolicy[F]
  private val sinceLast                       = cfg.invalidation.lapsedSinceLastInteraction.getOrElse(0.millis).toMillis

  /**
    * Returns the caller group set either from the claimset, in cache or from the user info endpoint of the provided
    * realm.
    *
    * @param token     the token to use as key for the group information
    * @param claimsSet the set of claims in the access token
    * @param realm     the realm against which the caller is authenticated
    * @param exp       an optional expiry for the token
    */
  def groups(token: AccessToken, claimsSet: JWTClaimsSet, realm: ActiveRealm, exp: Option[Instant]): F[Set[Group]] = {
    if (claimsSet.getClaims.containsKey("groups")) fromClaimSet(claimsSet, realm.id).pure[F]
    else fromUserInfo(token, realm, exp)
  }

  private def fromClaimSet(claimsSet: JWTClaimsSet, realmId: Label): Set[Group] = {
    import scala.jdk.CollectionConverters._
    val strings = Try(claimsSet.getStringListClaim("groups").asScala.toList)
      .filter(_ != null)
      .map(_.map(_.trim))
      .map(_.filterNot(_.isEmpty))
      .recoverWith { case _ => Try(claimsSet.getStringClaim("groups").split(",").map(_.trim).toList) }
      .toOption
      .map(_.toSet)
      .getOrElse(Set.empty)
    strings.map(s => Group(s, realmId.value))
  }

  private def fromUserInfo(token: AccessToken, realm: ActiveRealm, exp: Option[Instant]): F[Set[Group]] = {
    cache.get(token).flatMap {
      case Some(set) => F.pure(set)
      case None =>
        for {
          set <- fetch(token, realm).retryingOnAllErrors[Throwable]
          _   <- cache.put(token, set, exp.getOrElse(Instant.now().plusMillis(sinceLast)))
        } yield set
    }
  }

  private def fetch(token: AccessToken, realm: ActiveRealm): F[Set[Group]] = {
    def fromSet(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[Set[String]]("groups").map(_.map(_.trim).filterNot(_.isEmpty))
    def fromCsv(cursor: HCursor): Decoder.Result[Set[String]] =
      cursor.get[String]("groups").map(_.split(",").map(_.trim).filterNot(_.isEmpty).toSet)

    val req = Get(realm.userInfoEndpoint.asUri).addCredentials(OAuth2BearerToken(token.value))
    hc(req)
      .map { json =>
        val stringGroups = fromSet(json.hcursor) orElse fromCsv(json.hcursor) getOrElse Set.empty[String]
        stringGroups.map(str => Group(str, realm.id.value))
      }
      .recoverWith {
        case UnexpectedUnsuccessfulHttpResponse(resp, body) =>
          if (resp.status == StatusCodes.Unauthorized || resp.status == StatusCodes.Forbidden) {
            logger.warn(s"A provided client token was rejected by the OIDC provider, reason: '$body'")
            F.raiseError(IamError.InvalidAccessToken(TokenRejection.InvalidAccessToken))
          } else {
            logger.warn(
              s"A call to get the groups from the OIDC provider failed unexpectedly, status '${resp.status}', reason: '$body'"
            )
            F.raiseError(IamError.InternalError("Unable to extract group information from the OIDC provider."))
          }
      }
  }
}

private[realms] class GroupsCache[F[_]](ref: StateMachine[F, String, GroupsCache.State, GroupsCache.Command, Unit])(
    implicit F: Monad[F]
) {

  def put(token: AccessToken, groups: Set[Group], exp: Instant): F[Unit] =
    ref.evaluate(token.value, Write(groups, exp)) >> F.unit

  def get(token: AccessToken): F[GroupsCache.State] =
    ref.currentState(token.value)
}

private[realms] object GroupsCache {

  type State   = Option[Set[Group]]
  type Command = Write

  final case class Write(groups: Set[Group], exp: Instant)

  private val evaluate: (State, Command) => State = { case (_, cmd) => Some(cmd.groups) }

  final def apply[F[_]: Effect: Timer](implicit as: ActorSystem, cfg: GroupsConfig): F[GroupsCache[F]] = {
    val sinceLast                            = cfg.invalidation.lapsedSinceLastInteraction
    implicit val retryPolicy: RetryPolicy[F] = cfg.retry.retryPolicy[F]

    val invalidationStrategy: StopStrategy[State, Command] =
      StopStrategy(
        sinceLast, {
          case (_, _, _, None)      => None
          case (_, _, _, Some(cmd)) =>
            // passivate with the minimum duration (either the token expiry or the passivation timeout)
            val now   = Instant.now().toEpochMilli
            val delta = Math.max(0L, Math.min(cmd.exp.toEpochMilli - now, sinceLast.getOrElse(0.millis).toMillis))
            Some(delta.millis)
        }
      )
    AkkaStateMachine
      .sharded[F]("groups", None, evaluate.toEitherF[F], invalidationStrategy, cfg.akkaStateMachineConfig, cfg.shards)
      .map(new GroupsCache(_))
  }
}

object Groups {

  /**
    * Constructs a Groups instance with its underlying cache from the provided implicit args.
    */
  final def apply[F[_]: Effect: Timer]()(
      implicit as: ActorSystem,
      cfg: GroupsConfig,
      hc: HttpClient[F, Json]
  ): F[Groups[F]] =
    GroupsCache[F].map(new Groups(_))
}
