package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model.Secret.{DecryptedString, EncryptedString}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.testkit.{CirceLiteral, EitherValuable}
import io.circe.Json
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SecretSpec extends AnyWordSpecLike with Matchers with EitherValuable with CirceLiteral with OptionValues {

  "A Secret" should {

    "not expose its value when calling toString" in {
      Secret.encrypted("value").toString shouldEqual "SECRET"
      Secret.decrypted("value").toString shouldEqual "SECRET"
    }

    "be converted to Json" in {
      Secret.encrypted("value").asJson shouldEqual "value".asJson
      Secret.decrypted("value").asJson shouldEqual Json.Null
    }

    "be converted from Json-LD" in {
      val expanded = ExpandedJsonLd.unsafe(BNode.random, json"""{"@value": "value"}""".asObject.value)
      expanded.to[DecryptedString].rightValue shouldEqual Secret.decrypted("value")
      expanded.to[EncryptedString].rightValue shouldEqual Secret.decrypted("value")
    }

    "be extracted from Json" in {
      val json = "value".asJson
      json.as[DecryptedString].rightValue shouldEqual Secret.decrypted("value")
      json.as[EncryptedString].rightValue shouldEqual Secret.encrypted("value")
    }

  }

}
