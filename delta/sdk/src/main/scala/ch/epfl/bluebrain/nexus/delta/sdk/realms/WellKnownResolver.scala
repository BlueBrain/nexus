package ch.epfl.bluebrain.nexus.delta.sdk.realms

import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.sdk.implicits.*
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IllegalEndpointFormat, IllegalGrantTypeFormat, IllegalIssuerFormat, IllegalJwkFormat, IllegalJwksUriFormat, NoValidKeysFound, UnsuccessfulJwksResponse, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{GrantType, RealmRejection, WellKnown}
import com.nimbusds.jose.jwk.{JWK, KeyType}
import io.circe.generic.semiauto.*
import io.circe.{CursorOp, Decoder, Json}
import org.http4s.Uri

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
  def apply(fetch: Uri => IO[Json])(configUri: Uri): IO[WellKnown] = {
    import GrantType.Snake.*

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
      json.hcursor
        .get[Uri]("jwks_uri")
        .leftMap(df => IllegalJwksUriFormat(configUri, CursorOp.opsToPath(df.history)))
        .flatMap { uri =>
          Either.cond(uri.scheme.isDefined, uri, IllegalJwksUriFormat(configUri, ".jwks_uri"))
        }

    def endpoints(json: Json): Either[RealmRejection, Endpoints] =
      Endpoints
        .endpointsDecoder(json.hcursor)
        .leftMap(df => IllegalEndpointFormat(configUri, CursorOp.opsToPath(df.history)))

    def fetchJwkKeys(jwkUri: Uri): IO[Set[Json]] =
      for {
        json      <- fetch(jwkUri).adaptError(_ => UnsuccessfulJwksResponse(jwkUri))
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
      json       <- fetch(configUri).adaptError(_ => UnsuccessfulOpenIdConfigResponse(configUri))
      issuer     <- IO.fromEither(issuer(json))
      grantTypes <- IO.fromEither(grantTypes(json))
      jwkUri     <- IO.fromEither(jwksUri(json))
      jwkKeys    <- fetchJwkKeys(jwkUri)
      endPoints  <- IO.fromEither(endpoints(json))
    } yield model.WellKnown(
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
