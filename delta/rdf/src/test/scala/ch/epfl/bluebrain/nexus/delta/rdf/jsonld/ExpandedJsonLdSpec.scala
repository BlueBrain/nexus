package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.{BNode, Iri}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{schema, xsd}
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An expanded Json-LD" should {
    val compacted        = jsonContentOf("compacted.json")
    val context          = compacted.topContextValueOrEmpty
    val expectedExpanded = jsonContentOf("expanded.json")

    "be constructed successfully" in {
      JsonLd.expand(compacted).accepted shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed successfully without @id" in {
      val name             = vocab + "name"
      val expectedExpanded = json"""[{"@type": ["${schema.Person}"], "$name": [{"@value": "Me"} ] } ]"""
      val compacted        = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)
      val expanded         = JsonLd.expand(compacted).accepted
      expanded shouldEqual JsonLd.expandedUnsafe(expectedExpanded, expanded.rootId)
      expanded.rootId shouldBe a[BNode]
    }

    "be constructed successfully with remote contexts" in {
      val compacted = jsonContentOf("/jsonld/expanded/input-with-remote-context.json")
      JsonLd.expand(compacted).accepted shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed successfully with injected @id" in {
      val compactedNoId = compacted.removeKeys("id")
      JsonLd.expand(compactedNoId).accepted.replaceId(iri) shouldEqual JsonLd.expandedUnsafe(expectedExpanded, iri)
    }

    "be constructed empty (ignoring @id)" in {
      val compacted = json"""{"@id": "$iri"}"""
      val expanded  = JsonLd.expand(compacted).accepted
      expanded shouldEqual JsonLd.expandedUnsafe(json"""[ {} ]""", expanded.rootId)
      expanded.rootId shouldBe a[BNode]
    }

    "fail to be constructed when there are multiple root objects" in {
      val wrongInput = jsonContentOf("/jsonld/expanded/wrong-input-multiple-roots.json")
      JsonLd.expand(wrongInput).rejectedWith[UnexpectedJsonLd]
    }

    "replace @id" in {
      val newIri   = iri"http://example.com/myid"
      val expanded = JsonLd.expand(compacted).accepted.replaceId(newIri)
      expanded.rootId shouldEqual newIri
      expanded.json shouldEqual expectedExpanded.replace(keywords.id -> iri.asJson, newIri.asJson)
    }

    "fetch root @type" in {
      val compacted = json"""{"@id": "$iri", "@type": "Person"}""".addContext(context.contextObj)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.types shouldEqual List(schema.Person)

      val compactedMultipleTypes = json"""{"@id": "$iri", "@type": ["Person", "Hero"]}""".addContext(context.contextObj)
      val resultMultiple         = JsonLd.expand(compactedMultipleTypes).accepted
      resultMultiple.types shouldEqual List(schema.Person, vocab + "Hero")
    }

    "fetch @id values" in {
      val compacted = json"""{"@id": "$iri", "customid": "Person"}""".addContext(context.contextObj)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.ids(vocab + "customid") shouldEqual List(base + "Person")

      val compactedMultipleCustomId =
        json"""{"@id": "$iri", "customid": ["Person", "Hero"]}""".addContext(context.contextObj)
      val resultMultiple            = JsonLd.expand(compactedMultipleCustomId).accepted
      resultMultiple.ids(vocab + "customid") shouldEqual List(base + "Person", base + "Hero")
    }

    "fetch @value values" in {
      val compacted = json"""{"@id": "$iri", "tags": "a"}""".addContext(context.contextObj)
      val expanded  = JsonLd.expand(compacted).accepted
      expanded.literals[String](vocab + "tags") shouldEqual List("a")

      val compactedMultipleTags = json"""{"@id": "$iri", "tags": ["a", "b", "c"]}""".addContext(context.contextObj)
      val resultMultiple        = JsonLd.expand(compactedMultipleTags).accepted
      resultMultiple.literals[String](vocab + "tags") shouldEqual List("a", "b", "c")
    }

    "return empty fetching non existing keys" in {
      val expanded = JsonLd.expand(compacted.removeKeys("@type")).accepted
      expanded.literals[String](vocab + "non-existing") shouldEqual List.empty[String]
      expanded.ids(vocab + "non-existing") shouldEqual List.empty[Iri]
      expanded.types shouldEqual List.empty[Iri]
    }

    "add @id value" in {
      val expanded = JsonLd.expandedUnsafe(json"""[{"@id": "$iri"}]""", iri)
      val friends  = vocab + "friends"
      val batman   = base + "batman"
      val robin    = base + "robin"
      expanded.add(friends, batman).add(friends, robin).json shouldEqual
        json"""[{"@id": "$iri", "$friends": [{"@id": "$batman"}, {"@id": "$robin"} ] } ]"""
    }

    "add @type Iri to existing @type" in {
      val (person, animal, hero) = (schema.Person, schema + "Animal", schema + "Hero")
      val expanded               = JsonLd.expandedUnsafe(json"""[{"@id": "$iri", "@type": ["$person", "$animal"] } ]""", iri)
      expanded.addType(hero).types shouldEqual List(person, animal, hero)
    }

    "add @type Iri" in {
      val expanded = JsonLd.expandedUnsafe(json"""[{"@id": "$iri"}]""", iri)
      expanded.addType(schema.Person).types shouldEqual List(schema.Person)
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
      val result   = expanded.toCompacted(context).accepted
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "be converted to compacted form without @id" in {
      val compacted = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)

      val expanded = JsonLd.expand(compacted).accepted
      val result   = expanded.toCompacted(context).accepted
      result.rootId shouldEqual expanded.rootId
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "return self when attempted to convert again to expanded form" in {
      val expanded = JsonLd.expand(compacted).accepted
      expanded.toExpanded.accepted should be theSameInstanceAs expanded
    }

    "be converted to graph" in {
      val expanded = JsonLd.expand(compacted).accepted
      val graph    = expanded.toGraph.accepted
      val expected = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.rootNode shouldEqual iri
      graph.toNTriples.accepted.toString should equalLinesUnordered(expected)
    }
  }
}
