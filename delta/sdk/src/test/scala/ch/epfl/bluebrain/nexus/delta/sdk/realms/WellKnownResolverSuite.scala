package ch.epfl.bluebrain.nexus.delta.sdk.realms

import akka.http.scaladsl.model.{HttpRequest, Uri}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpUnexpectedError
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.GrantType
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.GrantType._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.RealmRejection.{IllegalEndpointFormat, IllegalIssuerFormat, IllegalJwkFormat, IllegalJwksUriFormat, NoValidKeysFound, UnsuccessfulJwksResponse, UnsuccessfulOpenIdConfigResponse}
import ch.epfl.bluebrain.nexus.testkit.ce.IOFromMap
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.circe.Json
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.syntax.KeyOps

class WellKnownResolverSuite extends NexusSuite with IOFromMap with CirceLiteral {

  private val openIdUri = Uri("https://localhost/auth/realms/master/.well-known/openid-configuration")
  private val jwksUri   = Uri("https://localhost/auth/realms/master/protocol/openid-connect/certs")
  private val issuer    = "https://localhost/auth/realms/master"

  private val authorizationUri = Uri("https://localhost/auth")
  private val tokenUri         = Uri("https://localhost/auth/token")
  private val userInfoUri      = Uri("https://localhost/auth/userinfo")
  private val revocationUri    = Uri("https://localhost/auth/revoke")
  private val endSessionUri    = Uri("https://localhost/auth/logout")

  private val publicKey =
    new RSAKeyGenerator(2048)
      .keyID("123")
      .generate()
      .toPublicJWK
      .toJSONString

  private val publicKeyJson = json"$publicKey"

  private val validJwks = json"""{ "keys": [ $publicKey ] }"""

  private def resolveWellKnown(openIdConfig: Json, jwks: Json) =
    WellKnownResolver(
      ioFromMap(
        Map(
          openIdUri -> openIdConfig,
          jwksUri   -> jwks
        ),
        (_: Uri) => HttpUnexpectedError(HttpRequest(), "Failed")
      )
    )(openIdUri)

  private val defaultConfig =
    json"""
      {
        "issuer": "$issuer",
        "jwks_uri": "$jwksUri",
        "grant_types_supported": [
          "authorization_code",
          "implicit",
          "refresh_token",
          "password",
          "client_credentials"
        ],
        "authorization_endpoint": "$authorizationUri",
        "token_endpoint": "$tokenUri",
        "userinfo_endpoint": "$userInfoUri"
      }
    """

  private val fullConfig = defaultConfig deepMerge Json.obj(
    "revocation_endpoint"  := revocationUri,
    "end_session_endpoint" := endSessionUri
  )

  test("Succeed with the expected grant types") {
    val config = defaultConfig

    val expectedGrantTypes = Set(AuthorizationCode, Implicit, RefreshToken, Password, ClientCredentials)
    resolveWellKnown(
      config,
      validJwks
    ).map { wk =>
      assertEquals(wk.issuer, issuer)
      assertEquals(wk.grantTypes, expectedGrantTypes)
      assertEquals(wk.keys, Set(publicKeyJson))
    }
  }

  test("Succeed with empty grant types") {
    val emptyGrantTypes = defaultConfig deepMerge Json.obj("grant_types_supported" -> Json.arr())
    resolveWellKnown(
      emptyGrantTypes,
      validJwks
    ).map { wk =>
      assertEquals(wk.grantTypes, Set.empty[GrantType])
    }
  }

  test("Succeed with no grant types field") {
    val noGrantTypes = defaultConfig.removeKeys("grant_types_supported")
    resolveWellKnown(
      noGrantTypes,
      validJwks
    ).map { wk =>
      assertEquals(wk.grantTypes, Set.empty[GrantType])
    }
  }

  test("Populate the different endpoints") {
    resolveWellKnown(
      fullConfig,
      validJwks
    ).map { wk =>
      assertEquals(wk.authorizationEndpoint, authorizationUri)
      assertEquals(wk.tokenEndpoint, tokenUri)
      assertEquals(wk.userInfoEndpoint, userInfoUri)
      assertEquals(wk.revocationEndpoint, Some(revocationUri))
      assertEquals(wk.endSessionEndpoint, Some(endSessionUri))
    }
  }

  test("Fail if the client returns a bad response") {
    val alwaysFail = WellKnownResolver(_ => IO.raiseError(HttpUnexpectedError(HttpRequest(), "Failed")))(openIdUri)
    alwaysFail.interceptEquals(UnsuccessfulOpenIdConfigResponse(openIdUri))
  }

  test("Fail if the openid contains an invalid issuer") {
    val invalidIssuer = defaultConfig deepMerge Json.obj("issuer" := " ")

    resolveWellKnown(
      invalidIssuer,
      validJwks
    ).interceptEquals(IllegalIssuerFormat(openIdUri, ".issuer"))
  }

  test("Fail if the openid contains an issuer with an invalid type") {
    val invalidIssuer = defaultConfig deepMerge Json.obj("issuer" := 42)

    resolveWellKnown(
      invalidIssuer,
      validJwks
    ).interceptEquals(IllegalIssuerFormat(openIdUri, ".issuer"))
  }

  test("Fail if the openid contains an issuer with an invalid type") {
    val invalidIssuer = defaultConfig deepMerge Json.obj("jwks_uri" := "asd")

    resolveWellKnown(
      invalidIssuer,
      validJwks
    ).interceptEquals(IllegalJwksUriFormat(openIdUri, ".jwks_uri"))
  }

  List(
    "authorization_endpoint",
    "token_endpoint",
    "userinfo_endpoint",
    "revocation_endpoint",
    "end_session_endpoint"
  ).foreach { key =>
    test(s"Fail if the openid contains an invalid '$key' endpoint") {
      val invalidEndpoint = fullConfig deepMerge Json.obj(key := 42)
      resolveWellKnown(
        invalidEndpoint,
        validJwks
      ).interceptEquals(IllegalEndpointFormat(openIdUri, s".$key"))
    }
  }

  List("authorization_endpoint", "token_endpoint", "userinfo_endpoint").foreach { key =>
    test(s"Fail if the openid does not contain the '$key' endpoint") {
      val invalidEndpoint = fullConfig.removeKeys(key)
      resolveWellKnown(
        invalidEndpoint,
        validJwks
      ).interceptEquals(IllegalEndpointFormat(openIdUri, s".$key"))
    }
  }

  test("Fail if there is a bad response for the jwks document") {
    val invalidJwksUri = Uri("https://localhost/invalid")
    val invalidJwks    = defaultConfig deepMerge Json.obj("jwks_uri" := invalidJwksUri)

    resolveWellKnown(
      invalidJwks,
      validJwks
    ).interceptEquals(UnsuccessfulJwksResponse(invalidJwksUri))
  }

  test("Fail if the jwks document has an incorrect format") {
    resolveWellKnown(
      defaultConfig,
      Json.obj()
    ).interceptEquals(IllegalJwkFormat(jwksUri))
  }

  test("Fail if the jwks document has an incorrect format") {
    resolveWellKnown(
      defaultConfig,
      Json.obj()
    ).interceptEquals(IllegalJwkFormat(jwksUri))
  }

  test("Fail if the jwks document has no keys") {
    resolveWellKnown(
      defaultConfig,
      Json.obj("keys" -> Json.arr())
    ).interceptEquals(NoValidKeysFound(jwksUri))
  }

  test("Fail if the jwks document has no valid keys") {
    resolveWellKnown(
      defaultConfig,
      Json.obj("keys" -> Json.arr(Json.fromString("incorrect")))
    ).interceptEquals(NoValidKeysFound(jwksUri))
  }

}
