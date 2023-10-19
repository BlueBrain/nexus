package ch.epfl.bluebrain.nexus.delta.rdf.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.DeltaSpec
import io.circe.Json
import io.circe.syntax._

class JsonUtilsSpec extends DeltaSpec with Fixtures {

  "A Json" should {

    "be empty" in {
      forAll(List(Json.obj(), Json.arr(), "".asJson)) { json =>
        json.isEmpty() shouldEqual true
      }
    }

    "not be empty" in {
      forAll(List(json"""{"k": "v"}""", Json.arr(json"""{"k": "v"}"""), "abc".asJson, 2.asJson)) { json =>
        json.isEmpty() shouldEqual false
      }
    }

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

    "replace matching key value" in {
      val json =
        json"""[{"key": 1, "key2": {"key": "value"}}, { "key": 1, "@context": {"@vocab": "${vocab.value}"} }]"""

      json.replace("key" -> 1, "other") shouldEqual
        json"""[{"key": "other", "key2": {"key": "value"}}, { "key": "other", "@context": {"@vocab": "${vocab.value}"} }]"""
    }

    "replace matching key" in {
      val json =
        json"""[{"key": 1, "key2": {"key": "value"}}, { "key": 1, "@context": {"@vocab": "${vocab.value}"} }]"""

      json.replaceKeyWithValue("key", "other") shouldEqual
        json"""[{"key": "other", "key2": {"key": "other"}}, { "key": "other", "@context": {"@vocab": "${vocab.value}"} }]"""

      json.replaceKeyWithValue("key4", "other") shouldEqual json
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

    "extract String value" in {
      val list = List(json"""{"key": "value", "key2": "value2"}""", json"""{"key": ["value"], "key2": "value2"}""")
      forAll(list) { json =>
        json.getIgnoreSingleArray[String]("key").rightValue shouldEqual "value"
        json.getIgnoreSingleArrayOr("key")("other").rightValue shouldEqual "value"
        json.getIgnoreSingleArrayOr("key3")("other").rightValue shouldEqual "other"
      }
    }

    "map value of all instances of a key" in {
      val json     =
        json"""{
          "key1": "somevalue",
          "key2": "anothervalue",
          "key3": {
            "key2": {
              "key2": "somethign"
              }
            }
          }
          """
      val expected =
        json"""
        {
          "key1": "somevalue",
          "key2": "mapped",
          "key3": {
            "key2": "mapped"
            }
        }
          """
      json.mapAllKeys("key2", _ => "mapped".asJson) shouldEqual expected
    }

    "add key and value only if NonEmpty" in {
      json"""{"k": "v"}""".addIfNonEmpty("k2", List(1, 2)) shouldEqual json"""{"k": "v", "k2": [1,2]}"""
      json"""{"k": "v"}""".addIfNonEmpty("k2", List.empty[String]) shouldEqual json"""{"k": "v"}"""
    }
    "add key and value only if exists" in {
      json"""{"k": "v"}""".addIfExists("k2", Some("v2")) shouldEqual json"""{"k": "v", "k2": "v2"}"""
      json"""{"k": "v"}""".addIfExists[String]("k2", None) shouldEqual json"""{"k": "v"}"""
    }
  }

  "A Json object" should {
    "add key and value only if NonEmpty" in {
      jobj"""{"k": "v"}""".addIfNonEmpty("k2", List(1, 2)) shouldEqual jobj"""{"k": "v", "k2": [1,2]}"""
      jobj"""{"k": "v"}""".addIfNonEmpty("k2", List.empty[String]) shouldEqual jobj"""{"k": "v"}"""
    }
    "add key and value only if exists" in {
      jobj"""{"k": "v"}""".addIfExists("k2", Some("v2")) shouldEqual jobj"""{"k": "v", "k2": "v2"}"""
      jobj"""{"k": "v"}""".addIfExists[String]("k2", None) shouldEqual jobj"""{"k": "v"}"""
    }
  }

  "A Json cursor" should {

    "extract String value" in {
      val list = List(
        json"""{"k": {"key": "value"}, "key2": "value2"}""",
        json"""{"k": {"key": ["value"] }, "key2": "value2"}"""
      )
      forAll(list) { json =>
        val hc = json.hcursor
        hc.downField("k").getIgnoreSingleArray[String]("key").rightValue shouldEqual "value"
        hc.downField("k").getIgnoreSingleArrayOr("key")("other").rightValue shouldEqual "value"
        hc.downField("k").getIgnoreSingleArrayOr("key3")("other").rightValue shouldEqual "other"
      }
    }
  }
}
