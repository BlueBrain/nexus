package ch.epfl.bluebrain.nexus.iam.realms

import akka.http.scaladsl.client.RequestBuilding._
import cats.data.EitherT
import cats.effect.Effect
import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.iam.types.GrantType
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.nimbusds.jose.jwk.{JWK, KeyType}
import io.circe.generic.semiauto._
import io.circe.{CursorOp, Decoder, Json}

import scala.util.Try
import scala.util.control.NonFatal

/**
  * Data type that represents the required well known configuration for an OIDC provider.
  *
  * @param issuer                the issuer identifier
  * @param grantTypes            the collection of supported grant types
  * @param keys                  the collection of keys
  * @param authorizationEndpoint the authorization endpoint
  * @param tokenEndpoint         the token endpoint
  * @param userInfoEndpoint      the user info endpoint
  * @param revocationEndpoint    an optional revocation endpoint
  * @param endSessionEndpoint    an optional end session endpoint
  */
final case class WellKnown(
    issuer: String,
    grantTypes: Set[GrantType],
    keys: Set[Json],
    authorizationEndpoint: Url,
    tokenEndpoint: Url,
    userInfoEndpoint: Url,
    revocationEndpoint: Option[Url],
    endSessionEndpoint: Option[Url]
)

object WellKnown {

  final private case class Endpoints(
      authorization_endpoint: Url,
      token_endpoint: Url,
      userinfo_endpoint: Url,
      revocation_endpoint: Option[Url],
      end_session_endpoint: Option[Url]
  )
  private object Endpoints {
    implicit val endpointsDecoder: Decoder[Endpoints] =
      deriveDecoder[Endpoints]
  }

  /**
    * Attempts to build a [[WellKnown]] instance by following the provided openid config address and validating the
    * responses along the way.
    *
    * @param address the address of the openid configuration
    */
  def apply[F[_]](address: Url)(implicit cl: HttpClient[F, Json], F: Effect[F]): F[Either[Rejection, WellKnown]] = {
    import GrantType.Snake._
    def fetchConfig: EitherT[F, Rejection, Json]                     =
      EitherT(cl(Get(address.asUri)).map[Either[Rejection, Json]](Right.apply).handleErrorWith {
        case NonFatal(_) => F.pure(Left(UnsuccessfulOpenIdConfigResponse(address)))
      })
    def issuer(json: Json): Either[Rejection, String]                =
      json.hcursor
        .get[String]("issuer")
        .leftMap(df => IllegalIssuerFormat(address, CursorOp.opsToPath(df.history)))
        .flatMap {
          case iss if iss.trim.isEmpty => Left(IllegalIssuerFormat(address, ".issuer"))
          case iss                     => Right(iss)
        }
    def grantTypes(json: Json): Either[Rejection, Set[GrantType]]    =
      json.hcursor
        .get[Option[Set[GrantType]]]("grant_types_supported")
        .map(_.getOrElse(Set.empty))
        .leftMap(df => IllegalGrantTypeFormat(address, CursorOp.opsToPath(df.history)))
    def jwksUrl(json: Json): Either[Rejection, Url]                  =
      json.hcursor
        .get[String]("jwks_uri")
        .leftMap(df => IllegalJwksUriFormat(address, CursorOp.opsToPath(df.history)))
        .flatMap(str => Url(str).leftMap(_ => IllegalJwksUriFormat(address, ".jwks_uri")))
    def endpoints(json: Json): Either[Rejection, Endpoints]          =
      Endpoints
        .endpointsDecoder(json.hcursor)
        .leftMap(df => IllegalEndpointFormat(address, CursorOp.opsToPath(df.history)))
    def fetchJwks(address: Url): EitherT[F, Rejection, Json]         =
      EitherT(cl(Get(address.asUri)).map[Either[Rejection, Json]](Right.apply).handleErrorWith {
        case NonFatal(_) => F.pure(Left(UnsuccessfulJwksResponse(address)))
      })
    def jwks(address: Url, json: Json): Either[Rejection, Set[Json]] =
      json.hcursor
        .get[Set[Json]]("keys")
        .leftMap(_ => IllegalJwkFormat(address))
    def selectValidKeys(keys: Set[Json], address: Url): Either[Rejection, Set[Json]] = {
      val validKeys = keys.foldLeft(Set.empty[Json]) {
        case (valid, key) =>
          if (Try(JWK.parse(key.noSpaces)).exists(_.getKeyType == KeyType.RSA)) valid + key
          else valid
      }
      if (validKeys.isEmpty) Left(NoValidKeysFound(address))
      else Right(validKeys)
    }

    fetchConfig.flatMap { cfgJson =>
      val tupled: Either[Rejection, (String, Set[GrantType], Url, Endpoints)] =
        (issuer(cfgJson), grantTypes(cfgJson), jwksUrl(cfgJson), endpoints(cfgJson)).tupled
      tupled match {
        case Left(rej)                              => EitherT.leftT[F, WellKnown](rej)
        case Right((iss, gts, jwksAddress, endpts)) =>
          fetchJwks(jwksAddress).flatMap { jwksJson =>
            jwks(jwksAddress, jwksJson).flatMap(keys => selectValidKeys(keys, jwksAddress)) match {
              case Left(rej)   => EitherT.leftT[F, WellKnown](rej)
              case Right(keys) =>
                val wk = WellKnown(
                  iss,
                  gts,
                  keys,
                  endpts.authorization_endpoint,
                  endpts.token_endpoint,
                  endpts.userinfo_endpoint,
                  endpts.revocation_endpoint,
                  endpts.end_session_endpoint
                )
                EitherT.rightT[F, Rejection](wk)
            }
          }
      }
    }.value
  }
}
