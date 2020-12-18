package ch.epfl.bluebrain.nexus.delta.service.realms

import akka.http.scaladsl.model.{HttpRequest, Uri}
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.GrantType._
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmRejection.{IllegalEndpointFormat, IllegalGrantTypeFormat, IllegalIssuerFormat, IllegalJwkFormat, IllegalJwksUriFormat, NoValidKeysFound, UnsuccessfulJwksResponse, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import io.circe.parser._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Inspectors, OptionValues}

class WellKnownResolverSpec
    extends AnyWordSpecLike
    with IOValues
    with Inspectors
    with OptionValues
    with TestHelpers
    with Matchers {

  import WellKnownResolverSpec._

  "A WellKnown" should {

    def resolveWellKnown(openIdConfig: Json, jwks: Json) =
      WellKnownResolver(
        ioFromMap(
          Map(
            openIdUri -> openIdConfig,
            jwksUri   -> jwks
          ),
          (_: Uri) => HttpUnexpectedError(HttpRequest(), "Failed")
        )
      )(openIdUri)

    def alwaysFail =
      WellKnownResolver(
        ioFromMap(
          Map.empty[Uri, Json],
          (_: Uri) => HttpUnexpectedError(HttpRequest(), "Failed")
        )
      )(openIdUri)

    "be constructed correctly" when {
      "the openid config is valid" in {
        val wk = resolveWellKnown(
          validOpenIdConfig,
          validJwks
        ).accepted
        wk.issuer shouldEqual issuer
        wk.grantTypes shouldEqual grantTypes
        wk.keys shouldEqual Set(publicKeyJson)
      }

      "the openid contains empty grant_types" in {
        val wk = resolveWellKnown(
          validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr())),
          validJwks
        ).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }

      "the openid contains no grant_types" in {
        val wk = resolveWellKnown(
          validOpenIdConfig.hcursor.downField("grant_types_supported").delete.top.value,
          validJwks
        ).accepted
        wk.grantTypes shouldEqual Set.empty[GrantType]
      }

      "the openid contains the expected endpoints" in {
        val wk = resolveWellKnown(
          fullOpenIdConfig,
          validJwks
        ).accepted
        wk.issuer shouldEqual issuer
        wk.grantTypes shouldEqual grantTypes
        wk.keys shouldEqual Set(publicKeyJson)
        wk.authorizationEndpoint shouldEqual authorizationUri
        wk.tokenEndpoint shouldEqual tokenUri
        wk.userInfoEndpoint shouldEqual userInfoUri
        wk.revocationEndpoint.value shouldEqual revocationUri
        wk.endSessionEndpoint.value shouldEqual endSessionUri
      }
    }

    "fail to construct" when {
      "the client records a bad response" in {
        val rej = alwaysFail.rejectedWith[UnsuccessfulOpenIdConfigResponse]
        rej.document shouldEqual openIdUri
      }

      "the openid contains an invalid issuer" in {
        val rej = resolveWellKnown(
          validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromString(" "))),
          validJwks
        ).rejectedWith[IllegalIssuerFormat]

        rej.document shouldEqual openIdUri
        rej.location shouldEqual ".issuer"
      }

      "the openid contains a issuer with an invalid type" in {
        val rej = resolveWellKnown(
          validOpenIdConfig.deepMerge(Json.obj("issuer" -> Json.fromInt(3))),
          validJwks
        ).rejectedWith[IllegalIssuerFormat]

        rej.document shouldEqual openIdUri
        rej.location shouldEqual ".issuer"
      }
    }

    "the openid contains an invalid jwks_uri" in {
      val rej = resolveWellKnown(
        validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromString("asd"))),
        validJwks
      ).rejectedWith[IllegalJwksUriFormat]

      rej.document shouldEqual openIdUri
      rej.location shouldEqual ".jwks_uri"
    }

    "the openid contains a jwks_uri with an invalid type" in {
      val rej = resolveWellKnown(
        validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromInt(3))),
        validJwks
      ).rejectedWith[IllegalJwksUriFormat]

      rej.document shouldEqual openIdUri
      rej.location shouldEqual ".jwks_uri"
    }

    "the openid contains a invalid grant_types" in {
      val rej = resolveWellKnown(
        validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.fromString("incorrect"))),
        validJwks
      ).rejectedWith[IllegalGrantTypeFormat]

      rej.document shouldEqual openIdUri
      rej.location shouldEqual ".grant_types_supported"
    }

    "the openid contains no valid grant_types" in {
      val rej = resolveWellKnown(
        validOpenIdConfig.deepMerge(Json.obj("grant_types_supported" -> Json.arr(Json.fromString("incorrect")))),
        validJwks
      ).rejectedWith[IllegalGrantTypeFormat]

      rej.document shouldEqual openIdUri
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
        val rej = resolveWellKnown(
          fullOpenIdConfig.deepMerge(Json.obj(key -> Json.fromInt(3))),
          validJwks
        ).rejectedWith[IllegalEndpointFormat]

        rej.document shouldEqual openIdUri
        rej.location shouldEqual s".$key"
      }
    }

    "the openid does not contain required endpoints" in {
      forAll(List("authorization_endpoint", "token_endpoint", "userinfo_endpoint")) { key =>
        val rej = resolveWellKnown(
          fullOpenIdConfig.hcursor.downField(key).delete.top.value,
          validJwks
        ).rejectedWith[IllegalEndpointFormat]

        rej.document shouldEqual openIdUri
        rej.location shouldEqual s".$key"

      }
    }

    "the client returns a bad response for the jwks document" in {
      val invalidJwksUri = Uri("https://localhost/invalid")
      val rej            = resolveWellKnown(
        validOpenIdConfig.deepMerge(Json.obj("jwks_uri" -> Json.fromString(invalidJwksUri.toString()))),
        validJwks
      ).rejectedWith[UnsuccessfulJwksResponse]

      rej.document shouldEqual invalidJwksUri
    }

    "the jwks document has an incorrect format" in {
      val rej = resolveWellKnown(
        validOpenIdConfig,
        Json.obj()
      ).rejectedWith[IllegalJwkFormat]

      rej.document shouldEqual jwksUri
    }

    "the jwks document has no keys" in {
      val rej = resolveWellKnown(
        validOpenIdConfig,
        Json.obj("keys" -> Json.arr())
      ).rejectedWith[NoValidKeysFound]

      rej.document shouldEqual jwksUri
    }

    "the jwks document has incorrect keys" in {
      val rej = resolveWellKnown(
        validOpenIdConfig,
        Json.obj("keys" -> Json.arr(Json.fromString("incorrect")))
      ).rejectedWith[NoValidKeysFound]

      rej.document shouldEqual jwksUri
    }
  }

}

object WellKnownResolverSpec extends EitherValuable {

  private val openIdUri = Uri("https://localhost/auth/realms/master/.well-known/openid-configuration")
  private val jwksUri   = Uri("https://localhost/auth/realms/master/protocol/openid-connect/certs")
  private val issuer    = "https://localhost/auth/realms/master"

  private val authorizationUri = Uri("https://localhost/auth")
  private val tokenUri         = Uri("https://localhost/auth/token")
  private val userInfoUri      = Uri("https://localhost/auth/userinfo")
  private val revocationUri    = Uri("https://localhost/auth/revoke")
  private val endSessionUri    = Uri("https://localhost/auth/logout")

  private val validOpenIdConfigString =
    s"""
       | {
       |   "issuer": "$issuer",
       |   "jwks_uri": "$jwksUri",
       |   "grant_types_supported": [
       |     "authorization_code",
       |     "implicit",
       |     "refresh_token",
       |     "password",
       |     "client_credentials"
       |   ],
       |   "authorization_endpoint": "$authorizationUri",
       |   "token_endpoint": "$tokenUri",
       |   "userinfo_endpoint": "$userInfoUri"
       | }
    """.stripMargin
  private val validOpenIdConfig       = parse(validOpenIdConfigString).rightValue

  private val fullOpenIdConfigString =
    s"""
       | {
       |   "issuer": "$issuer",
       |   "jwks_uri": "$jwksUri",
       |   "grant_types_supported": [
       |     "authorization_code",
       |     "implicit",
       |     "refresh_token",
       |     "password",
       |     "client_credentials"
       |   ],
       |   "authorization_endpoint": "$authorizationUri",
       |   "token_endpoint": "$tokenUri",
       |   "userinfo_endpoint": "$userInfoUri",
       |   "revocation_endpoint": "$revocationUri",
       |   "end_session_endpoint": "$endSessionUri"
       | }
    """.stripMargin
  private val fullOpenIdConfig       = parse(fullOpenIdConfigString).rightValue

  private val publicKey =
    new RSAKeyGenerator(2048)
      .keyID("123")
      .generate()
      .toPublicJWK
      .toJSONString

  private val publicKeyJson = parse(publicKey).rightValue

  private val validJwksString =
    s"""
       | {
       |   "keys": [
       |     $publicKey
       |   ]
       | }
  """.stripMargin

  private val validJwks = parse(validJwksString).rightValue

  private val grantTypes = Set(AuthorizationCode, Implicit, RefreshToken, Password, ClientCredentials)

}
