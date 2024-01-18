package ch.epfl.bluebrain.nexus.storage.auth

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod._
import ch.epfl.bluebrain.nexus.storage.utils.Randomness
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral.circeLiteralSyntax
import com.nimbusds.jose.Algorithm
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{JWK, JWKSet, KeyUse, RSAKey}
import com.nimbusds.jose.util.{Base64, Base64URL}
import munit.FunSuite
import pureconfig.ConfigSource

import scala.annotation.nowarn
import scala.jdk.CollectionConverters._

class AuthorizationMethodSuite extends FunSuite with Randomness {

  private def generateKey: RSAKey = new RSAKeyGenerator(2048).keyID(randomString()).generate()

  private def parseConfig(value: String) =
    ConfigSource.string(value).at("authorization").load[AuthorizationMethod]

  test("Parse successfully for the anonymous method") {
    val config = parseConfig(
      """
        |authorization {
        |  type = anonymous
        |}
        |""".stripMargin
    )
    assertEquals(config, Right(Anonymous))
  }

  test("Parse successfully for the verify token method") {
    val key1: JWK = generateKey.toPublicJWK
    val key2: JWK = generateKey.toPublicJWK

    val config = parseConfig(
      s"""
        |authorization {
        |  type = verify-token
        |  issuer = bbp
        |  subject = admin
        |  audiences = [dev, staging]
        |  keys = [ ${key1.toJSONString}, ${key2.toJSONString}]
        |}
        |""".stripMargin
    )

    val expectedAudiences = Some(NonEmptySet.of("dev", "staging"))
    val expectedKeySet    = new JWKSet(List(key1, key2).asJava)
    val expected          = VerifyToken("bbp", "admin", expectedAudiences, expectedKeySet)

    assertEquals(config, Right(expected))
  }

  test("Parse successfully without audiences") {
    val key1: JWK = generateKey.toPublicJWK

    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  issuer = bbp
         |  subject = admin
         |  keys = [ ${key1.toJSONString} ]
         |}
         |""".stripMargin
    )

    val expectedAudiences = None
    val expectedKeySet    = new JWKSet(key1)
    val expected          = VerifyToken("bbp", "admin", expectedAudiences, expectedKeySet)

    assertEquals(config, Right(expected))
  }

  // To allow passing key config as properties
  test("Parse successfully for X.509 certificate chain (x5c) passed as a list within a string") {
    val kid       = "UTJGGczwZ76W83wzM5k6LdnnVUuGEGJ2DTxTjyQD9-Y"
    val alg       = "RSA-OAEP"
    val use       = "enc"
    val n         =
      "ueE-G8QrHYleaPY6GB02eFU0B7SEb5hp3j4skFNw6VREaqJT2Tf5iFQE6ZeVwoYpT01bb3W-hg2TMnB_9mqMieWk51JdgIgPdNfR6jTGY233vmIMj1fEHdF5yzzJWlBYzj0vXmdOXkWmoTerQWuZeooJYvAhk7u_nW1KyDQAU99CSLBynlR_EOL13ERoGjipY0Mpew0cMUsntJgjTuRPjNR6-zdQfSvT3Fb_tqs1xPnWc_o8JCgRAJIxi4MauHto-dPNbArniGb65Rwsnw_lAD63ZZRDqXxTWsixqOX18SbcuCteYJ3FyZdHjkV8QmuBwrSarVu0jzfH-o_pcwA0vw"
    val e         = "AQAB"
    val x5cCert   =
      "MIIClTCCAX0CBgGJbf5HzDANBgkqhkiG9w0BAQsFADAOMQwwCgYDVQQDDANTQk8wHhcNMjMwNzE5MTE1MDI4WhcNMzMwNzE5MTE1MjA4WjAOMQwwCgYDVQQDDANTQk8wggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC54T4bxCsdiV5o9joYHTZ4VTQHtIRvmGnePiyQU3DpVERqolPZN/mIVATpl5XChilPTVtvdb6GDZMycH/2aoyJ5aTnUl2AiA9019HqNMZjbfe+YgyPV8Qd0XnLPMlaUFjOPS9eZ05eRaahN6tBa5l6igli8CGTu7+dbUrINABT30JIsHKeVH8Q4vXcRGgaOKljQyl7DRwxSye0mCNO5E+M1Hr7N1B9K9PcVv+2qzXE+dZz+jwkKBEAkjGLgxq4e2j5081sCueIZvrlHCyfD+UAPrdllEOpfFNayLGo5fXxJty4K15gncXJl0eORXxCa4HCtJqtW7SPN8f6j+lzADS/AgMBAAEwDQYJKoZIhvcNAQELBQADggEBAEwGjfiypbacK6qGuOJv/ctZRQloCcfQqQVYjk2OVJtmi6P2MVKV1JT3CvGPhpT2qV31rGUvxgdYWPkyO11Tl7Kv1YuYo3kuIzYUsoFSrP4YkXH08SYZZmn7igorkV6EPQq+Wuxgkf7VxT8DsEw6hvN7m0PX6UCTOJu5QLSLJNZzChqVUbH/FTKYj529NDdUqyk8cJCy8vYVPOrgTEXTNFdYilO1pUcgnKYVZ/A6swLOVpA0nUtNaNTYLEOE/yPMSDCHpN5x9f7a2FmKnLhb7Hof610X37uehAcrAvKbNlFnyVe+5Wf4T9Hr6OFSGObIf4eBIs3z57aUzJZrpSLwhsg="
    val x5t       = "O_JGgGoSjSwv1JDr8TRb9YlhMIY"
    val `x5t#256` = "8sxasFIoAqkNYIK1rrXszvyiE2MNkxDU9qMposUHbBM"

    @nowarn
    val expectedRSAKey: RSAKey = new RSAKey.Builder(Base64URL.from(n), Base64URL.from(e))
      .keyID(kid)
      .algorithm(Algorithm.parse(alg))
      .keyUse(KeyUse.parse(use))
      .x509CertThumbprint(Base64URL.from(x5t))
      .x509CertSHA256Thumbprint(Base64URL.from(`x5t#256`))
      .x509CertChain(List(Base64.from(x5cCert)).asJava)
      .build()

    val expected = VerifyToken("bbp", "admin", None, new JWKSet(expectedRSAKey))

    val inputKey: String =
      json"""
        {
              "kid": "$kid",
              "kty": "RSA",
              "alg": "$alg",
              "use": "$use",
              "n": "$n",
              "e": "$e",
              "x5c": "[\\"$x5cCert\\"]",
              "x5t": "$x5t",
              "x5t#S256": "${`x5t#256`}"
        }
      """.toString()

    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  issuer = bbp
         |  subject = admin
         |  keys = [ $inputKey ]
         |}
         |""".stripMargin
    )

    assertEquals(config, Right(expected))
  }

  test("Fail to parse the config if the issuer is missing") {
    val key1: JWK = generateKey.toPublicJWK

    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  subject = admin
         |  keys = [ ${key1.toJSONString} ]
         |}
         |""".stripMargin
    )

    assert(config.isLeft, "Parsing must fail with an missing issuer")
  }

  test("Fail to parse the config if the subject is missing") {
    val key1: JWK = generateKey.toPublicJWK

    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  issuer = bbp
         |  keys = [ ${key1.toJSONString} ]
         |}
         |""".stripMargin
    )

    assert(config.isLeft, "Parsing must fail with an missing subject")
  }

  test("Fail to parse the config if the key is invalid") {
    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  issuer = bbp
         |  subject = admin
         |  keys = [ "xxx" ]
         |}
         |""".stripMargin
    )

    assert(config.isLeft, "Parsing must fail with an invalid key")
  }

  test("Fail to parse the config without a key") {
    val config = parseConfig(
      s"""
         |authorization {
         |  type = verify-token
         |  issuer = bbp
         |  subject = admin
         |  keys = [ ]
         |}
         |""".stripMargin
    )

    assert(config.isLeft, "Parsing must fail without a key")
  }

}
