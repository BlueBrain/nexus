package ch.epfl.bluebrain.nexus.iam.realms

import java.util.UUID

import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.HttpResponse
import cats.effect.IO
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, UnexpectedUnsuccessfulHttpResponse}
import ch.epfl.bluebrain.nexus.iam.realms.RealmRejection._
import ch.epfl.bluebrain.nexus.iam.realms.WellKnownSpec._
import ch.epfl.bluebrain.nexus.iam.types.GrantType
import ch.epfl.bluebrain.nexus.iam.types.GrantType._
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
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

  private def httpMock(openIdConfig: Json, jwks: Json): HttpClient[IO, Json] = {
    val cl = mock[HttpClient[IO, Json]]
    cl.apply(Get(openIdUrlString)) shouldReturn IO.pure(openIdConfig)
    cl.apply(Get(jwksUrlString)) shouldReturn IO.pure(jwks)
    cl
  }

  "A WellKnown" should {

    "be constructed correctly" when {
      "the openid config is valid" in {
        implicit val cl = httpMock(validOpenIdConfig, validJwks)
        val wk          = WellKnown[IO](openIdUrl).accepted
        wk.issuer shouldEqual issuer
        wk.grantTypes shouldEqual grantTypes
        wk.keys shouldEqual Set(publicKeyJson)
      }
      "the openid contains empty grant_types" in {
        implicit val cl =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr())), validJwks)
        val wk          = WellKnown[IO](openIdUrl).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }
      "the openid contains no grant_types" in {
        implicit val cl =
          httpMock(validOpenIdConfig.hcursor.downField("grant_types_supported").delete.top.value, validJwks)
        val wk          = WellKnown[IO](openIdUrl).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }
      "the openid contains the expected endpoints" in {
        implicit val cl = httpMock(fullOpenIdConfig, validJwks)
        val wk          = WellKnown[IO](openIdUrl).accepted
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
        implicit val cl = mock[HttpClient[IO, Json]]
        cl.apply(Get(openIdUrlString)) shouldReturn IO.raiseError(
          UnexpectedUnsuccessfulHttpResponse(HttpResponse(), "")
        )
        val rej         = WellKnown[IO](openIdUrl).rejected[UnsuccessfulOpenIdConfigResponse]
        rej.document shouldEqual openIdUrl
      }
      "the openid contains an invalid issuer" in {
        implicit val cl = httpMock(validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromString(" "))), validJwks)
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalIssuerFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".issuer"
      }
      "the openid contains a issuer with an invalid type" in {
        implicit val cl = httpMock(validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromInt(3))), validJwks)
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalIssuerFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".issuer"
      }
      "the openid contains an invalid jwks_uri" in {
        implicit val cl =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromString("asd"))), validJwks)
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalJwksUriFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".jwks_uri"
      }
      "the openid contains a jwks_uri with an invalid type" in {
        implicit val cl =
          httpMock(validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromInt(3))), validJwks)
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalJwksUriFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".jwks_uri"
      }
      "the openid contains a invalid grant_types" in {
        implicit val cl =
          httpMock(
            validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.fromString("incorrect"))),
            validJwks
          )
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalGrantTypeFormat]
        rej.document shouldEqual openIdUrl
        rej.location shouldEqual ".grant_types_supported"
      }
      "the openid contains no valid grant_types" in {
        implicit val cl = httpMock(
          validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr(Json.fromString("incorrect")))),
          validJwks
        )
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalGrantTypeFormat]
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
          implicit val cl = httpMock(fullOpenIdConfig.deepMerge(Json.obj(key -> Json.fromInt(3))), validJwks)
          val rej         = WellKnown[IO](openIdUrl).rejected[IllegalEndpointFormat]
          rej.document shouldEqual openIdUrl
          rej.location shouldEqual s".$key"
        }
      }
      "the openid does not contain required endpoints" in {
        forAll(List("authorization_endpoint", "token_endpoint", "userinfo_endpoint")) { key =>
          val cfg         = fullOpenIdConfig.hcursor.downField(key).delete.top.value
          implicit val cl = httpMock(cfg, validJwks)
          val rej         = WellKnown[IO](openIdUrl).rejected[IllegalEndpointFormat]
          rej.document shouldEqual openIdUrl
          rej.location shouldEqual s".$key"
        }
      }
      "the client returns a bad response for the jwks document" in {
        implicit val cl = mock[HttpClient[IO, Json]]
        cl.apply(Get(openIdUrlString)) shouldReturn IO.pure(validOpenIdConfig)
        cl.apply(Get(jwksUrlString)) shouldReturn IO.raiseError(UnexpectedUnsuccessfulHttpResponse(HttpResponse(), ""))
        val rej         = WellKnown[IO](openIdUrl).rejected[UnsuccessfulJwksResponse]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has an incorrect format" in {
        implicit val cl = httpMock(validOpenIdConfig, Json.obj())
        val rej         = WellKnown[IO](openIdUrl).rejected[IllegalJwkFormat]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has no keys" in {
        implicit val cl = httpMock(validOpenIdConfig, Json.obj("keys" -> Json.arr()))
        val rej         = WellKnown[IO](openIdUrl).rejected[NoValidKeysFound]
        rej.document shouldEqual jwksUrl
      }
      "the jwks document has incorrect keys" in {
        implicit val cl = httpMock(validOpenIdConfig, Json.obj("keys" -> Json.arr(Json.fromString("incorrect"))))
        val rej         = WellKnown[IO](openIdUrl).rejected[NoValidKeysFound]
        rej.document shouldEqual jwksUrl
      }
    }

  }

}

//noinspection TypeAnnotation
object WellKnownSpec {
  import EitherValues._
  def genUrl = Url(s"https://localhost/auth/realms/master/.well-known/${UUID.randomUUID()}").rightValue

  val openIdUrlString = "https://localhost/auth/realms/master/.well-known/openid-configuration"
  val openIdUrl       = Url(openIdUrlString).rightValue
  val openIdUrl2      = genUrl
  val openIdUrl3      = genUrl
  val jwksUrlString   = "https://localhost/auth/realms/master/protocol/openid-connect/certs"
  val jwksUrl         = Url(jwksUrlString).rightValue
  val issuer          = "https://localhost/auth/realms/master"
  val deprUrlString   = "https://localhost/auth/realms/deprecated/.well-known/openid-configuration"

  val authorizationUrl = Url("https://localhost/auth").rightValue
  val tokenUrl         = Url("https://localhost/auth/token").rightValue
  val userInfoUrl      = Url("https://localhost/auth/userinfo").rightValue
  val revocationUrl    = Url("https://localhost/auth/revoke").rightValue
  val endSessionUrl    = Url("https://localhost/auth/logout").rightValue

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
      |   "authorization_endpoint": "${authorizationUrl.asUri}",
      |   "token_endpoint": "${tokenUrl.asUri}",
      |   "userinfo_endpoint": "${userInfoUrl.asUri}"
      | }
    """.stripMargin
  val validOpenIdConfig       = parse(validOpenIdConfigString).rightValue

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
       |   "authorization_endpoint": "${authorizationUrl.asUri}",
       |   "token_endpoint": "${tokenUrl.asUri}",
       |   "userinfo_endpoint": "${userInfoUrl.asUri}",
       |   "revocation_endpoint": "${revocationUrl.asUri}",
       |   "end_session_endpoint": "${endSessionUrl.asUri}"
       | }
    """.stripMargin
  val fullOpenIdConfig       = parse(fullOpenIdConfigString).rightValue

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
       |   "authorization_endpoint": "${authorizationUrl.asUri}",
       |   "token_endpoint": "${tokenUrl.asUri}",
       |   "userinfo_endpoint": "${userInfoUrl.asUri}"
       | }
    """.stripMargin
  val deprecatedOpenIdConfig       = parse(deprecatedOpenIdConfigString).rightValue

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
