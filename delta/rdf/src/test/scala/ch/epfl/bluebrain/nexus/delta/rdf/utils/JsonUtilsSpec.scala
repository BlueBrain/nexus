package ch.epfl.bluebrain.nexus.delta.rdf.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonUtilsSpec extends AnyWordSpecLike with Matchers with Fixtures {
  "A Json" should {

    "remove top keys on a Json object" in {
      val json = json"""{"key": "value", "@context": {"@vocab": "${vocab.value}"}, "key2": {"key": "value"}}"""
      json.removeKeys("key", "@context") shouldEqual json"""{"key2": {"key": "value"}}"""
      json.removeKeys("key", "@context", "key2") shouldEqual json"""{}"""
    }

    "remove top keys on a Json array" in {
      val json = json"""[{"key": "value", "key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
      json.removeKeys("key", "@context") shouldEqual json"""[{"key2": {"key": "value"}}, {}]"""
    }

    "remove keys on a Json object" in {
      val json = json"""{"key": "value", "@context": {"@vocab": "${vocab.value}"}, "key2": {"key": "value"}}"""
      json.removeAllKeys("key", "@context") shouldEqual json"""{"key2": {}}"""
      json.removeKeys("key", "@context", "key2") shouldEqual json"""{}"""
    }

    "remove all matching key values" in {
      val json =
        json"""[{"key": 1, "key2": {"key": "value"}}, { "key": 1, "@context": {"@vocab": "${vocab.value}"} }]"""

      json.removeAll("@vocab" -> vocab.value.toString, "key" -> "value") shouldEqual
        json"""[{"key": 1, "key2": {}}, { "key": 1, "@context": {} }]"""
      json.removeAll("key" -> 1) shouldEqual
        json"""[{"key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
    }

    "remove all matching keys on a Json array" in {
      val json = json"""[{"key": "value", "key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
      json.removeAllKeys("key", "@context") shouldEqual json"""[{"key2": {}}, {}]"""
    }

    "remove all matching values on a Json array" in {
      val json = json"""[{"key": "value", "key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
      json.removeAllValues("value", vocab.value.toString) shouldEqual
        json"""[{"key2": {}}, { "@context": {} }]"""
    }

    "extract the passed keys from the Json array" in {
      val json =
        json"""[{"key": "value", "key2": {"key": {"key21": "value"}}}, { "@context": {"@vocab": "${vocab.value}", "key3": {"key": "value2"}} }]"""
      json.extractValuesFrom("key") shouldEqual Set("value".asJson, json"""{"key21": "value"}""", "value2".asJson)
    }

    "sort its keys" in {
      implicit val ordering: JsonKeyOrdering =
        JsonKeyOrdering(topKeys = Seq("@id", "@type"), bottomKeys = Seq("_rev", "_project"))

      val json =
        json"""{
                "name": "Maria",
                "_rev": 5,
                "age": 30,
                "@id": "mariaId",
                "friends": [
                  { "_rev": 1, "name": "Pablo", "_project": "a", "age": 20 },
                  { "name": "Laura", "_project": "b", "age": 23, "_rev": 2, "@id": "lauraId" }
                ],
                "@type": "Person"
              }"""

      json.sort shouldEqual
        json"""{
                "@id": "mariaId",
                "@type": "Person",
                "age": 30,
                "friends": [
                  { "age": 20, "name": "Pablo", "_rev": 1, "_project": "a" },
                  { "@id": "lauraId", "age": 23, "name": "Laura", "_rev": 2, "_project": "b" }
                ],
                "name": "Maria",
                "_rev": 5
              }"""

    }
  }

}
