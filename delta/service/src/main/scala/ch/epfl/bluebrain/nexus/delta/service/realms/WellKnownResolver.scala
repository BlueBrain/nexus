package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{IllegalEndpointFormat, IllegalGrantTypeFormat, IllegalIssuerFormat, IllegalJwkFormat, IllegalJwksUriFormat, NoValidKeysFound, UnsuccessfulJwksResponse, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, RealmRejection, WellKnown}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import com.nimbusds.jose.jwk.{JWK, KeyType}
import io.circe.generic.semiauto._
import io.circe.{CursorOp, Decoder, Json}
import monix.bio.IO

import scala.util.Try

object WellKnownResolver {

  final private case class Endpoints(
      authorization_endpoint: Uri,
      token_endpoint: Uri,
      userinfo_endpoint: Uri,
      revocation_endpoint: Option[Uri],
      end_session_endpoint: Option[Uri]
  )
  private object Endpoints {
    implicit val endpointsDecoder: Decoder[Endpoints] =
      deriveDecoder[Endpoints]
  }

  /**
    * Constructs a WellKnown instance from an uri
    */
  def apply(fetch: Uri => IO[HttpClientError, Json])(configUri: Uri): IO[RealmRejection, WellKnown] = {
    import GrantType.Snake._

    def issuer(json: Json): Either[RealmRejection, String] =
      json.hcursor
        .get[String]("issuer")
        .leftMap(df => IllegalIssuerFormat(configUri, CursorOp.opsToPath(df.history)))
        .flatMap {
          case iss if iss.trim.isEmpty => Left(IllegalIssuerFormat(configUri, ".issuer"))
          case iss                     => Right(iss)
        }

    def grantTypes(json: Json): Either[RealmRejection, Set[GrantType]] =
      json.hcursor
        .get[Option[Set[GrantType]]]("grant_types_supported")
        .map(_.getOrElse(Set.empty))
        .leftMap(df => IllegalGrantTypeFormat(configUri, CursorOp.opsToPath(df.history)))

    def jwksUri(json: Json): Either[RealmRejection, Uri] =
      for {
        value <- json.hcursor
                   .get[String]("jwks_uri")
                   .leftMap(df => IllegalJwksUriFormat(configUri, CursorOp.opsToPath(df.history)))
        uri   <- value.toUri.leftMap(_ => IllegalJwksUriFormat(configUri, ".jwks_uri"))
        _     <- Either.cond(uri.isAbsolute, uri, IllegalJwksUriFormat(configUri, ".jwks_uri"))
      } yield uri

    def endpoints(json: Json): Either[RealmRejection, Endpoints] =
      Endpoints
        .endpointsDecoder(json.hcursor)
        .leftMap(df => IllegalEndpointFormat(configUri, CursorOp.opsToPath(df.history)))

    def fetchJwkKeys(jwkUri: Uri): IO[RealmRejection, Set[Json]] =
      for {
        json      <- fetch(jwkUri).mapError(_ => UnsuccessfulJwksResponse(jwkUri))
        keysJson  <- IO.fromEither(
                       json.hcursor
                         .get[Set[Json]]("keys")
                         .leftMap(_ => IllegalJwkFormat(jwkUri))
                     )
        validKeys <- {
          val validKeys = keysJson.foldLeft(Set.empty[Json]) { case (valid, key) =>
            valid ++
              Try(JWK.parse(key.noSpaces))
                .find(_.getKeyType == KeyType.RSA)
                .map(_ => key)

          }
          if (validKeys.isEmpty)
            IO.raiseError(NoValidKeysFound(jwkUri))
          else
            IO.pure(validKeys)
        }
      } yield validKeys

    for {
      json       <- fetch(configUri).mapError(_ => UnsuccessfulOpenIdConfigResponse(configUri))
      issuer     <- IO.fromEither(issuer(json))
      grantTypes <- IO.fromEither(grantTypes(json))
      jwkUri     <- IO.fromEither(jwksUri(json))
      jwkKeys    <- fetchJwkKeys(jwkUri)
      endPoints  <- IO.fromEither(endpoints(json))
    } yield WellKnown(
      issuer,
      grantTypes,
      jwkKeys,
      endPoints.authorization_endpoint,
      endPoints.token_endpoint,
      endPoints.userinfo_endpoint,
      endPoints.revocation_endpoint,
      endPoints.end_session_endpoint
    )
  }

}
