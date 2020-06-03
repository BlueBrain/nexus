package ch.epfl.bluebrain.nexus.iam.io

import akka.actor.{ActorSystem, ExtendedActorSystem}
import akka.serialization.Serialization
import akka.testkit.TestKit
import com.nimbusds.jose.jwk.JWKSet
import com.typesafe.config.ConfigFactory
import org.scalatest.TryValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JWKSetSerializerSpec
    extends TestKit(ActorSystem("JWKSetSerializerSpec", ConfigFactory.load("akka-test.conf")))
    with AnyWordSpecLike
    with Matchers
    with TryValues {

  private val serialization = new Serialization(system.asInstanceOf[ExtendedActorSystem])

  private val json =
    """
      |{
      |  "keys": [
      |    {
      |      "kid": "-JoF9COvvt7UhyhJMC-YlTF6piRlZgQKRQks5sPMKxw",
      |      "kty": "RSA",
      |      "alg": "RS256",
      |      "use": "sig",
      |      "n": "iEk11wBlv0I4pawBSY6ZYCLvwVslfCvjwvg5tIAg9n",
      |      "e": "AQAB"
      |    }
      |  ]
      |}
    """.stripMargin

  private val jwks = JWKSet.parse(json)

  "A JWKSetSerializer" should {

    "serialize and deserialize" in {
      val bytes = serialization.serialize(jwks).success.value
      val obj   = serialization.deserialize(bytes, classOf[JWKSet]).success.value
      jwks.toJSONObject shouldEqual obj.toJSONObject // JWKSet doesn't have a proper equals method
    }
  }
}
