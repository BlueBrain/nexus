package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{RootIriNotFound, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextFields
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.Json
import org.apache.jena.iri.IRI
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An expanded Json-LD" should {
    val compacted        = jsonContentOf("compacted.json")
    val context          = Json.obj(keywords.context -> compacted.topContextValueOrEmpty)
    val expectedExpanded = jsonContentOf("expanded.json")

    "be constructed successfully" in {
      JsonLd.expand(compacted).accepted shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed successfully with remote contexts" in {
      val compacted = jsonContentOf("/jsonld/expanded/input-with-remote-context.json")
      JsonLd.expand(compacted).accepted shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed successfully with injected @id" in {
      val compactedNoId = compacted.removeKeys("id")
      JsonLd.expand(compactedNoId, Some(iri)).accepted shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed empty" in {
      val compacted = json"""{"@id": "$iri"}"""
      JsonLd.expand(compacted, Some(iri)).accepted shouldEqual
        JsonLd.expandedUnsafe(json"""[ { "@id": "$iri" } ]""", iri)
    }

    "fail to be constructed when no root @id is present nor provided" in {
      val compactedNoId = compacted.removeKeys("id")
      JsonLd.expand(compactedNoId).rejected shouldEqual RootIriNotFound
    }

    "fail to be constructed when there are multiple root objects" in {
      val wrongInput = jsonContentOf("/jsonld/expanded/wrong-input-multiple-roots.json")
      JsonLd.expand(wrongInput).rejected shouldEqual
        UnexpectedJsonLd("Expected a Json Array with a single Json Object on expanded JSON-LD")
    }

    "fetch root @type" in {
      val compacted = json"""{"@id": "$iri", "@type": "Person"}""".addContext(context)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.types shouldEqual List(schema.Person)

      val compactedMultipleTypes = json"""{"@id": "$iri", "@type": ["Person", "Hero"]}""".addContext(context)
      val resultMultiple         = JsonLd.expand(compactedMultipleTypes).accepted
      resultMultiple.types shouldEqual List(schema.Person, vocab + "Hero")
    }

    "fetch @id values" in {
      val compacted = json"""{"@id": "$iri", "customid": "Person"}""".addContext(context)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.ids(vocab + "customid") shouldEqual List(base + "Person")

      val compactedMultipleCustomId = json"""{"@id": "$iri", "customid": ["Person", "Hero"]}""".addContext(context)
      val resultMultiple            = JsonLd.expand(compactedMultipleCustomId).accepted
      resultMultiple.ids(vocab + "customid") shouldEqual List(base + "Person", base + "Hero")
    }

    "fetch @value values" in {
      val compacted = json"""{"@id": "$iri", "tags": "a"}""".addContext(context)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.literals[String](vocab + "tags") shouldEqual List("a")

      val compactedMultipleTags = json"""{"@id": "$iri", "tags": ["a", "b", "c"]}""".addContext(context)
      val resultMultiple        = JsonLd.expand(compactedMultipleTags).accepted
      resultMultiple.literals[String](vocab + "tags") shouldEqual List("a", "b", "c")
    }

    "return empty fetching non existing keys" in {
      val expanded = JsonLd.expand(compacted.removeKeys("@type")).accepted
      expanded.literals[String](vocab + "non-existing") shouldEqual List.empty[String]
      expanded.ids(vocab + "non-existing") shouldEqual List.empty[IRI]
      expanded.types shouldEqual List.empty[IRI]
    }

    "add @id value" in {
      val expanded = JsonLd.expandedUnsafe(json"""[{"@id": "$iri"}]""", iri)
      val friends  = vocab + "friends"
      val batman   = base + "batman"
      val robin    = base + "robin"
      expanded.add(friends, batman).add(friends, robin).json shouldEqual
        json"""[{"@id": "$iri", "$friends": [{"@id": "$batman"}, {"@id": "$robin"} ] } ]"""
    }

    "add @value value" in {
      val expanded                       = JsonLd.expandedUnsafe(json"""[{"@id": "$iri"}]""", iri)
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

    "be converted to compacted form" in {
      val expanded = JsonLd.expand(compacted).accepted
      val result   = expanded.toCompacted(context, ContextFields.Skip).accepted
      result shouldEqual JsonLd.compact(expectedExpanded, context, iri, ContextFields.Skip).accepted
    }

    "return self when attempted to convert again to expanded form" in {
      val expanded = JsonLd.expand(compacted).accepted
      expanded.toExpanded.accepted should be theSameInstanceAs expanded
    }

    "be converted to graph" in {
      val expanded = JsonLd.expand(compacted).accepted
      val graph    = expanded.toGraph.accepted
      val expected = contentOf("ntriples.nt", "{bnode}" -> s"_:B${bNode(graph)}")
      graph.root shouldEqual iri
      graph.toNTriples.accepted.toString should equalLinesUnordered(expected)
    }
  }
}
