package ch.epfl.bluebrain.nexus.delta.rdf.jsonld

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CompactedJsonLdSpec extends AnyWordSpecLike with Matchers with Fixtures with Inspectors {

  "A compacted Json-LD" should {
    val expanded              = jsonContentOf("expanded.json")
    val context               = jsonContentOf("context.json").topContextValueOrEmpty
    val expectedCompacted     = jsonContentOf("compacted.json")
    val expandedNoId          = expanded.removeAll(keywords.id -> iri)
    val expectedCompactedNoId = expectedCompacted.removeAll("id" -> "john-doé")
    val rootBNode             = BNode.random

    "be constructed successfully" in {
      val compacted = CompactedJsonLd(iri, context, expanded).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual expectedCompacted.removeKeys(keywords.context)
      compacted.ctx shouldEqual context
      compacted.rootId shouldEqual iri
    }

    "be constructed successfully with a root blank node" in {
      val compacted = CompactedJsonLd(rootBNode, context, expandedNoId).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual expectedCompactedNoId.removeKeys(keywords.context)
      compacted.rootId shouldEqual rootBNode
    }

    "be constructed from a multi-root json" in {
      val input = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")

      CompactedJsonLd(iri, context, input).accepted.json.removeKeys(keywords.context) shouldEqual
        json"""{"@graph": [{"id": "john-doé", "@type": "Person"}, {"id": "batman", "@type": "schema:Hero"} ] }"""
    }

    "be framed from a multi-root json" in {
      val input = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")

      CompactedJsonLd.frame(iri, context, input).accepted.json.removeKeys(keywords.context) shouldEqual
        json"""{"id": "john-doé", "@type": "Person"}"""
    }

    "be constructed successfully from a multi-root json when using framing" in {
      val input     = jsonContentOf("/jsonld/compacted/input-multiple-roots.json")
      val compacted = CompactedJsonLd.frame(iri, context, input).accepted
      compacted.json.removeKeys(keywords.context) shouldEqual json"""{"id": "john-doé", "@type": "Person"}"""
    }

    "be converted to expanded form" in {
      val compacted = CompactedJsonLd(iri, context, expanded).accepted
      compacted.toExpanded.accepted shouldEqual ExpandedJsonLd(expanded).accepted
    }

    "be converted to expanded form with a root blank node" in {
      val compacted = CompactedJsonLd(rootBNode, context, expandedNoId).accepted
      compacted.toExpanded.accepted shouldEqual ExpandedJsonLd.expanded(expandedNoId).rightValue.replaceId(rootBNode)
    }

    "be converted to graph" in {
      val compacted = CompactedJsonLd(iri, context, expanded).accepted
      val graph     = compacted.toGraph.accepted
      val expected  = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.rootNode shouldEqual iri
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to graph with a root blank node" in {
      val compacted = CompactedJsonLd(rootBNode, context, expandedNoId).accepted
      val graph     = compacted.toGraph.accepted
      val expected  = contentOf("ntriples.nt", "bnode" -> bNode(graph).rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      graph.rootNode shouldEqual rootBNode
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }
  }
}
