package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable.VectorMap

class ExpandedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "An expanded Json-LD" should {
    val compacted        = jsonContentOf("compacted.json")
    val context          = compacted.topContextValueOrEmpty
    val expectedExpanded = jsonContentOf("expanded.json")

    "be constructed successfully" in {
      ExpandedJsonLd(compacted).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed successfully without @id" in {
      val name             = vocab + "name"
      val expectedExpanded = json"""[{"@type": ["${schema.Person}"], "$name": [{"@value": "Me"} ] } ]"""
      val compacted        = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)
      val expanded         = ExpandedJsonLd(compacted).accepted
      expanded.json shouldEqual expectedExpanded
      expanded.rootId shouldBe a[BNode]
    }

    "be constructed successfully with remote contexts" in {
      val compacted = jsonContentOf("/jsonld/expanded/input-with-remote-context.json")
      ExpandedJsonLd(compacted).accepted shouldEqual ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed successfully with injected @id" in {
      val compactedNoId = compacted.removeKeys("id")
      ExpandedJsonLd(compactedNoId).accepted.replaceId(iri) shouldEqual
        ExpandedJsonLd.expanded(expectedExpanded).rightValue
    }

    "be constructed empty (ignoring @id)" in {
      val compacted = json"""{"@id": "$iri"}"""
      val expanded  = ExpandedJsonLd(compacted).accepted
      expanded.json shouldEqual json"""[ {} ]"""
      expanded.rootId shouldBe a[BNode]
    }

    "be constructed with multiple root objects" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"http://example.com/batman"
      val john      = json"""{"@id": "$iri", "http://example.com/name": [{"@value": "John"} ] }""".asObject.value
      val batman    = json"""{"@id": "$batmanIri", "http://example.com/name": [{"@value": "Batman"} ] }""".asObject.value

      ExpandedJsonLd(multiRoot).accepted shouldEqual ExpandedJsonLd(iri, VectorMap(iri -> john, batmanIri -> batman))
    }

    "change its root object" in {
      val multiRoot = jsonContentOf("/jsonld/expanded/input-multiple-roots.json")
      val batmanIri = iri"http://example.com/batman"
      val john      = json"""{"@id": "$iri", "http://example.com/name": [{"@value": "John"} ] }""".asObject.value
      val batman    = json"""{"@id": "$batmanIri", "http://example.com/name": [{"@value": "Batman"} ] }""".asObject.value
      val expanded  = ExpandedJsonLd(multiRoot).accepted
      expanded.changeRootIfExists(batmanIri).value shouldEqual
        ExpandedJsonLd(batmanIri, VectorMap(batmanIri -> batman, iri -> john))
      expanded.changeRootIfExists(schema.base) shouldEqual None
    }

    "replace @id" in {
      val newIri   = iri"http://example.com/myid"
      val expanded = ExpandedJsonLd(compacted).accepted.replaceId(newIri)
      expanded.rootId shouldEqual newIri
      expanded.json shouldEqual expectedExpanded.replace(keywords.id -> iri, newIri)
    }

    "be converted to compacted form" in {
      val expanded = ExpandedJsonLd(compacted).accepted
      val result   = expanded.toCompacted(context).accepted
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "be converted to compacted form without @id" in {
      val compacted = json"""{"@type": "Person", "name": "Me"}""".addContext(context.contextObj)

      val expanded = ExpandedJsonLd(compacted).accepted
      val result   = expanded.toCompacted(context).accepted
      result.rootId shouldEqual expanded.rootId
      result.json.removeKeys(keywords.context) shouldEqual compacted.removeKeys(keywords.context)
    }

    "be empty" in {
      ExpandedJsonLd(json"""[{"@id": "http://example.com/id", "a": "b"}]""").accepted.isEmpty shouldEqual true
    }

    "not be empty" in {
      ExpandedJsonLd(compacted).accepted.isEmpty shouldEqual false
    }

    "be converted to graph" in {
      val expanded = ExpandedJsonLd(compacted).accepted
      val graph    = expanded.toGraph.rightValue
      val expected = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.rootNode shouldEqual iri
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }
  }
}
