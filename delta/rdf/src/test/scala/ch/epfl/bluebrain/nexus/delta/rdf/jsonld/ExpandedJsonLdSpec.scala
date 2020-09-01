package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLdError.{IdNotFound, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.apache.jena.iri.IRI
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An expanded Json-LD" should {
    val input    = jsonContentOf(s"/jsonld/expanded/input.json")
    val context  = Json.obj(keywords.context -> input.topContextValueOrEmpty)
    val expected = jsonContentOf(s"/jsonld/expanded/output.json")

    "be constructed successfully" in {
      val expanded = JsonLd.expand(input).runSyncUnsafe()
      expanded.json shouldEqual expected
      expanded.rootId shouldEqual iri
    }

    "be constructed successfully with remote contexts" in {
      val input    = jsonContentOf(s"/jsonld/expanded/input-with-remote-context.json")
      val expanded = JsonLd.expand(input).runSyncUnsafe()
      expanded.json shouldEqual expected
      expanded.rootId shouldEqual iri
    }

    "be constructed successfully with injected @id" in {
      val inputNoId = input.removeKeys("id")
      val expanded  = JsonLd.expand(inputNoId, Some(iri)).runSyncUnsafe()
      expanded.json shouldEqual expected
      expanded.rootId shouldEqual iri
    }

    "be constructed empty" in {
      val input    = json"""{"@id": "$iri"}"""
      val expanded = JsonLd.expand(input, Some(iri)).runSyncUnsafe()
      expanded.json shouldEqual json"""[ { "@id": "$iri" } ]"""
      expanded.rootId shouldEqual iri
    }

    "fail to be constructed when no root @id is present nor provided" in {
      val inputNoId = input.removeKeys("id")
      JsonLd.expand(inputNoId).attempt.runSyncUnsafe().leftValue shouldEqual IdNotFound
    }

    "fail to be constructed when there are multiple root objects" in {
      val wrongInput = jsonContentOf("/jsonld/expanded/wrong-input-multiple-roots.json")
      JsonLd.expand(wrongInput).attempt.runSyncUnsafe().leftValue shouldEqual
        UnexpectedJsonLd("Expected a Json Array with a single Json Object on expanded JSON-LD")
    }

    "fetch root @type" in {
      val input    = json"""{"@id": "$iri", "@type": "Person"}""".addContext(context)
      val expanded = JsonLd.expand(input).runSyncUnsafe()
      expanded.types shouldEqual List(schema + "Person")

      val inputMultiple  = json"""{"@id": "$iri", "@type": ["Person", "Hero"]}""".addContext(context)
      val resultMultiple = JsonLd.expand(inputMultiple).runSyncUnsafe()
      resultMultiple.types shouldEqual List(schema + "Person", vocab + "Hero")
    }

    "fetch @id values" in {
      val input    = json"""{"@id": "$iri", "customid": "Person"}""".addContext(context)
      val expanded = JsonLd.expand(input).runSyncUnsafe()
      expanded.ids(vocab + "customid") shouldEqual List(base + "Person")

      val inputMultiple  = json"""{"@id": "$iri", "customid": ["Person", "Hero"]}""".addContext(context)
      val resultMultiple = JsonLd.expand(inputMultiple).runSyncUnsafe()
      resultMultiple.ids(vocab + "customid") shouldEqual List(base + "Person", base + "Hero")
    }

    "fetch @value values" in {
      val input    = json"""{"@id": "$iri", "tags": "a"}""".addContext(context)
      val expanded = JsonLd.expand(input).runSyncUnsafe()
      expanded.literals[String](vocab + "tags") shouldEqual List("a")

      val inputMultiple  = json"""{"@id": "$iri", "tags": ["a", "b", "c"]}""".addContext(context)
      val resultMultiple = JsonLd.expand(inputMultiple).runSyncUnsafe()
      resultMultiple.literals[String](vocab + "tags") shouldEqual List("a", "b", "c")
    }

    "return empty fetching non existing keys" in {
      val expanded = JsonLd.expand(input.removeKeys("@type")).runSyncUnsafe()
      expanded.literals[String](vocab + "non-existing") shouldEqual List.empty[String]
      expanded.ids(vocab + "non-existing") shouldEqual List.empty[IRI]
      expanded.types shouldEqual List.empty[IRI]
    }

    "add @id value" in {
      val expanded = ExpandedJsonLd(JsonObject("@id" -> iri.asJson), iri)
      val friends  = vocab + "friends"
      val batman   = base + "batman"
      val robin    = base + "robin"
      expanded.add(friends, batman).add(friends, robin).json shouldEqual
        json"""[{"@id": "$iri", "$friends": [{"@id": "$batman"}, {"@id": "$robin"} ] } ]"""
    }

    "add @value value" in {
      val expanded                       = ExpandedJsonLd(JsonObject("@id" -> iri.asJson), iri)
      val tags                           = vocab + "tags"
      val (tag1, tag2, tag3, tag4, tag5) = ("first", 2, false, 30L, 3.14)
      expanded
        .add(tags, tag1)
        .add(tags, tag2, includeDataType = true)
        .add(tags, tag3)
        .add(tags, tag4)
        .add(tags, tag5, includeDataType = true)
        .json shouldEqual
        json"""[{"@id": "$iri", "$tags": [{"@value": "$tag1"}, {"@type": "${xsd.integer}", "@value": $tag2 }, {"@value": $tag3}, {"@value": $tag4}, {"@type": "${xsd.double}", "@value": $tag5 } ] } ]"""
    }
  }
}
