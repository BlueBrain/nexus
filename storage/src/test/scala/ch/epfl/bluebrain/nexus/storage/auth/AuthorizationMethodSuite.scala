package ch.epfl.bluebrain.nexus.storage.auth

import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod._
import ch.epfl.bluebrain.nexus.storage.utils.Randomness
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.{JWK, JWKSet, RSAKey}
import munit.FunSuite
import pureconfig.ConfigSource

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
