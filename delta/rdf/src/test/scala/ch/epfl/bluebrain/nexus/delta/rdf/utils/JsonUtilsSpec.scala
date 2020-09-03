package ch.epfl.bluebrain.nexus.delta.rdf.utils

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonUtilsSpec extends AnyWordSpecLike with Matchers with Fixtures {
  "A Json" should {

    "exclude removed top keys on a Json object" in {
      val json = json"""{"key": "value", "@context": {"@vocab": "${vocab.value}"}, "key2": {"key": "value"}}"""
      json.removeKeys("key", "@context") shouldEqual json"""{"key2": {"key": "value"}}"""
      json.removeKeys("key", "@context", "key2") shouldEqual json"""{}"""
    }

    "exclude removed top keys on a Json array" in {
      val json = json"""[{"key": "value", "key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
      json.removeKeys("key", "@context") shouldEqual json"""[{"key2": {"key": "value"}}, {}]"""
    }

    "exclude removed keys on a Json object" in {
      val json = json"""{"key": "value", "@context": {"@vocab": "${vocab.value}"}, "key2": {"key": "value"}}"""
      json.removeNestedKeys("key", "@context") shouldEqual json"""{"key2": {}}"""
      json.removeKeys("key", "@context", "key2") shouldEqual json"""{}"""
    }

    "exclude removed keys on a Json array" in {
      val json = json"""[{"key": "value", "key2": {"key": "value"}}, { "@context": {"@vocab": "${vocab.value}"} }]"""
      json.removeNestedKeys("key", "@context") shouldEqual json"""[{"key2": {}}, {}]"""
    }

    "return a set of Json that match the passed keys" in {
      val json =
        json"""[{"key": "value", "key2": {"key": {"key21": "value"}}}, { "@context": {"@vocab": "${vocab.value}", "key3": {"key": "value2"}} }]"""
      json.extractValuesFrom("key") shouldEqual Set("value".asJson, json"""{"key21": "value"}""", "value2".asJson)

    }
  }

}
