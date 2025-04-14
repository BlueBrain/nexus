package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec
import io.circe.Json

class ContextValueSpec extends BaseSpec with Fixtures {

  "A @context value" should {

    "be extracted" in {
      json"""[{"@context": {"@base": "${base.value}"}, "@id": "$iri", "age": 30}]""".topContextValueOrEmpty shouldEqual
        ContextValue(json"""{"@base": "${base.value}"}""")
      json"""{"@context": {"@base": "${base.value}"}, "@id": "$iri", "age": 30}""".topContextValueOrEmpty shouldEqual
        ContextValue(json"""{"@base": "${base.value}"}""")
      json"""{"@id": "$iri", "age": 30}""".topContextValueOrEmpty shouldEqual ContextValue.empty
    }

    "be empty" in {
      forAll(List(json"{}", json"[]", Json.fromString(""), Json.Null)) { json =>
        ContextValue(json).isEmpty shouldEqual true
      }
    }

    "not be empty" in {
      ContextValue(json"""{"@base": "${base.value}"}""").isEmpty shouldEqual false
    }

    "return its @context object" in {
      ContextValue(json"""{"@base": "${base.value}"}""").contextObj shouldEqual
        jobj"""{"@context": {"@base": "${base.value}"}}"""
    }

    "prevent merging two times the same context" in {
      ContextValue(contexts.metadata).merge(ContextValue(contexts.metadata)) shouldEqual ContextValue(contexts.metadata)
    }
  }

}
