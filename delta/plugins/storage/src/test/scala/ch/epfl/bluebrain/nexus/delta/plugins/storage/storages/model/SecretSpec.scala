package ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.model

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
      Secret("value").toString shouldEqual "SECRET"
    }

    "be converted to Json" in {
      Secret("value").asJson shouldEqual Json.Null
    }

    "be converted from Json-LD" in {
      val expanded = ExpandedJsonLd.unsafe(BNode.random, json"""{"@value": "value"}""".asObject.value)
      expanded.to[Secret[String]].rightValue shouldEqual Secret("value")
    }

    "be extracted from Json" in {
      val json = "value".asJson
      json.as[Secret[String]].rightValue shouldEqual Secret("value")
    }

  }

}
