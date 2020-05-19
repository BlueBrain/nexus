package ch.epfl.bluebrain.nexus.realms
import java.util.UUID

import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.{StatusCodes, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.clients.ClientError.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.clients.HttpClient
import ch.epfl.bluebrain.nexus.clients.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.realms.GrantType._
import ch.epfl.bluebrain.nexus.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.realms.WellKnownSpec._
import ch.epfl.bluebrain.nexus.util.{EitherValues, IOEitherValues}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import io.circe.parser._
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

//noinspection TypeAnnotation
class WellKnownSpec
    extends AnyWordSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with IOEitherValues
    with Inspectors
    with IdiomaticMockito {

  private def httpMock(openIdConfig: Json, jwks: Json): HttpClient[IO] = {
    val client = mock[HttpClient[IO]]
    client.executeParse[Json](Get(openIdUrlString)) shouldReturn IO.pure(openIdConfig)
    client.executeParse[Json](Get(jwksUrlString)) shouldReturn IO.pure(jwks)
    client
  }

  "A WellKnown" should {

    "be constructed correctly" when {
      "the openid config is valid" in {
        val client = httpMock(validOpenIdConfig, validJwks)
        val wk     = WellKnown(client, openIdUrl).accepted
        wk.issuer shouldEqual issuer
        wk.grantTypes shouldEqual grantTypes
        wk.keys shouldEqual Set(publicKeyJson)
      }
      "the openid contains empty grant_types" in {
        val client =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr())), validJwks)
        val wk = WellKnown(client, openIdUrl).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }
      "the openid contains no grant_types" in {
        val client =
          httpMock(validOpenIdConfig.hcursor.downField("grant_types_supported").delete.top.value, validJwks)
        val wk = WellKnown(client, openIdUrl).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }
      "the openid contains the expected endpoints" in {
        val client = httpMock(fullOpenIdConfig, validJwks)
        val wk     = WellKnown(client, openIdUrl).accepted
        wk.issuer shouldEqual issuer
        wk.grantTypes shouldEqual grantTypes
        wk.keys shouldEqual Set(publicKeyJson)
        wk.authorizationEndpoint shouldEqual authorizationUrl
        wk.tokenEndpoint shouldEqual tokenUrl
        wk.userInfoEndpoint shouldEqual userInfoUrl
        wk.revocationEndpoint.value shouldEqual revocationUrl
        wk.endSessionEndpoint.value shouldEqual endSessionUrl
      }
    }

    "fail to construct" when {
      "the client records a bad response" in {
        val client = mock[HttpClient[IO]]
        val req    = Get(openIdUrlString)
        client.executeParse[Json](req) shouldReturn
          IO.raiseError(HttpClientStatusError(req, StatusCodes.BadRequest, ""))
        val rej = WellKnown(client, openIdUrl).rejected[UnsuccessfulOpenIdConfigResponse]
        rej.document shouldEqual openIdUrl
      }
      "the openid contains an invalid issuer" in {
        val client = httpMock(validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromString(" "))), validJwks)
        val rej    = WellKnown(client, openIdUrl).rejected[IllegalIssuerFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".issuer"
      }
      "the openid contains a issuer with an invalid type" in {
        val client = httpMock(validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromInt(3))), validJwks)
        val rej    = WellKnown(client, openIdUrl).rejected[IllegalIssuerFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".issuer"
      }
      "the openid contains an invalid jwks_uri" in {
        val client =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromString("asd"))), validJwks)

        val rej = WellKnown(client, openIdUrl).rejected[IllegalJwksUriFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".jwks_uri"
      }
      "the openid contains a jwkpenid contains an invalid jwks_urs_uri with an invalid type" in {
        val client =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromInt(3))), validJwks)
        val rej = WellKnown(client, openIdUrl).rejected[IllegalJwksUriFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".jwks_uri"
      }
      "the openid contains a invalid grant_types" in {
        val client =
          httpMock(
            validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.fromString("incorrect"))),
            validJwks
          )
        val rej = WellKnown(client, openIdUrl).rejected[IllegalGrantTypeFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".grant_types_supported"
      }
      "the openid contains no valid grant_types" in {
        val client = httpMock(
          validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr(Json.fromString("incorrect")))),
          validJwks
        )
        val rej = WellKnown(client, openIdUrl).rejected[IllegalGrantTypeFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".grant_types_supported[0]"
      }
      "the openid contains an incorrect endpoint" in {
        forAll(
          List(
            "authorization_endpoint",
            "token_endpoint",
            "userinfo_endpoint",
            "revocation_endpoint",
            "end_session_endpoint"
          )
        ) { key =>
          val client = httpMock(fullOpenIdConfig.deepMerge(Json.obj(key -> Json.fromInt(3))), validJwks)
          val rej    = WellKnown(client, openIdUrl).rejected[IllegalEndpointFormat]
          rej.document shouldEqual openIdUrl
          rej.location shouldEqual s".$key"
        }
      }
      "the openid does not contain required endpoints" in {
        forAll(List("authorization_endpoint", "token_endpoint", "userinfo_endpoint")) { key =>
          val cfg    = fullOpenIdConfig.hcursor.downField(key).delete.top.value
          val client = httpMock(cfg, validJwks)
          val rej    = WellKnown(client, openIdUrl).rejected[IllegalEndpointFormat]
          rej.document shouldEqual openIdUrl
          rej.location shouldEqual s".$key"
        }
      }
      "the client returns a bad response for the jwks document" in {
        val client = mock[HttpClient[IO]]
        client.executeParse[Json](Get(openIdUrlString)) shouldReturn IO.pure(validOpenIdConfig)
        val req = Get(jwksUrlString)
        client.executeParse[Json](req) shouldReturn
          IO.raiseError(HttpClientStatusError(req, StatusCodes.BadRequest, ""))
        val rej = WellKnown(client, openIdUrl).rejected[UnsuccessfulJwksResponse]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has an incorrect format" in {
        val client = httpMock(validOpenIdConfig, Json.obj())
        val rej    = WellKnown[IO](client, openIdUrl).rejected[IllegalJwkFormat]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has no keys" in {
        val client = httpMock(validOpenIdConfig, Json.obj("keys" -> Json.arr()))
        val rej    = WellKnown(client, openIdUrl).rejected[NoValidKeysFound]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has incorrect keys" in {
        val client = httpMock(validOpenIdConfig, Json.obj("keys" -> Json.arr(Json.fromString("incorrect"))))
        val rej    = WellKnown(client, openIdUrl).rejected[NoValidKeysFound]
        rej.document shouldEqual jwksUrl
      }
    }

  }

}

//noinspection TypeAnnotation
object WellKnownSpec {
  import EitherValues._
  def genUrl = Uri(s"https://localhost/auth/realms/master/.well-known/${UUID.randomUUID()}")

  val openIdUrlString = "https://localhost/auth/realms/master/.well-known/openid-configuration"
  val openIdUrl       = Uri(openIdUrlString)
  val openIdUrl2      = genUrl
  val openIdUrl3      = genUrl
  val jwksUrlString   = "https://localhost/auth/realms/master/protocol/openid-connect/certs"
  val jwksUrl         = Uri(jwksUrlString)
  val issuer          = "https://localhost/auth/realms/master"
  val deprUrlString   = "https://localhost/auth/realms/deprecated/.well-known/openid-configuration"

  val authorizationUrl = Uri("https://localhost/auth")
  val tokenUrl         = Uri("https://localhost/auth/token")
  val userInfoUrl      = Uri("https://localhost/auth/userinfo")
  val revocationUrl    = Uri("https://localhost/auth/revoke")
  val endSessionUrl    = Uri("https://localhost/auth/logout")

  val validOpenIdConfigString =
    s"""
       | {
       |   "issuer": "$issuer",
       |   "jwks_uri": "$jwksUrlString",
       |   "grant_types_supported": [
       |     "authorization_code",
       |     "implicit",
       |     "refresh_token",
       |     "password",
       |     "client_credentials"
       |   ],
       |   "authorization_endpoint": "$authorizationUrl",
       |   "token_endpoint": "$tokenUrl",
       |   "userinfo_endpoint": "$userInfoUrl"
       | }
    """.stripMargin
  val validOpenIdConfig = parse(validOpenIdConfigString).rightValue

  val fullOpenIdConfigString =
    s"""
       | {
       |   "issuer": "$issuer",
       |   "jwks_uri": "$jwksUrlString",
       |   "grant_types_supported": [
       |     "authorization_code",
       |     "implicit",
       |     "refresh_token",
       |     "password",
       |     "client_credentials"
       |   ],
       |   "authorization_endpoint": "$authorizationUrl",
       |   "token_endpoint": "$tokenUrl",
       |   "userinfo_endpoint": "$userInfoUrl",
       |   "revocation_endpoint": "$revocationUrl",
       |   "end_session_endpoint": "$endSessionUrl"
       | }
    """.stripMargin
  val fullOpenIdConfig = parse(fullOpenIdConfigString).rightValue

  val deprecatedOpenIdConfigString =
    s"""
       | {
       |   "issuer": "deprecated",
       |   "jwks_uri": "$jwksUrlString",
       |   "grant_types_supported": [
       |     "authorization_code",
       |     "implicit",
       |     "refresh_token",
       |     "password",
       |     "client_credentials"
       |   ],
       |   "authorization_endpoint": "$authorizationUrl",
       |   "token_endpoint": "$tokenUrl",
       |   "userinfo_endpoint": "$userInfoUrl"
       | }
    """.stripMargin
  val deprecatedOpenIdConfig = parse(deprecatedOpenIdConfigString).rightValue

  val (kid, privateKey, publicKey) = {
    val rsaJWK = new RSAKeyGenerator(2048)
      .keyID("123")
      .generate()
    (rsaJWK.getKeyID, rsaJWK.toRSAPrivateKey, rsaJWK.toPublicJWK.toJSONString)
  }

  val publicKeyJson = parse(publicKey).rightValue

  val validJwksString =
    s"""
       | {
       |   "keys": [
       |     $publicKey
       |   ]
       | }
  """.stripMargin

  val validJwks = parse(validJwksString).rightValue

  val grantTypes = Set(AuthorizationCode, Implicit, RefreshToken, Password, ClientCredentials)

}
