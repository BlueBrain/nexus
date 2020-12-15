package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.ContextObject
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GraphSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A Graph" should {
    val expandedJson     = jsonContentOf("expanded.json")
    val expanded         = ExpandedJsonLd.expanded(expandedJson).rightValue
    val graph            = Graph(expanded).rightValue
    val bnode            = bNode(graph)
    val iriSubject       = subject(iri)
    val rootBNode        = BNode.random
    val expandedNoIdJson = expandedJson.removeAll(keywords.id -> iri)
    val expandedNoId     = ExpandedJsonLd.expanded(expandedNoIdJson).rightValue.replaceId(rootBNode)
    val graphNoId        = Graph(expandedNoId).rightValue
    val bnodeNoId        = bNode(graphNoId)

    "be created from expanded jsonld" in {
      Graph(expanded).rightValue.triples.size shouldEqual 16
    }

    "be created from expanded jsonld with a root blank node" in {
      Graph(expandedNoId).rightValue.triples.size shouldEqual 16
    }

    "return a filtered graph" in {
      val deprecated = predicate(schema + "deprecated")
      val name       = predicate(schema.name)
      val birthDate  = predicate(schema + "birthDate")
      val result     = graph.filter { case (s, p, _) => s == iriSubject && p.getNameSpace == schema.base.toString }
      result.triples shouldEqual
        Set(
          (iriSubject, deprecated, obj(false)),
          (iriSubject, name, obj("John Doe")),
          (iriSubject, birthDate, obj("1999-04-09T20:00Z"))
        )
    }

    "return an empty filtered graph" in {
      val none = predicate(schema + "none")
      graph.filter { case (s, p, _) => s == iriSubject && p == none }.triples shouldEqual Set.empty[Triple]
    }

    "return a Triple" in {
      val deprecated = predicate(schema + "deprecated")
      val result     = graph.find { case (s, p, _) => s == iriSubject && deprecated == p }.value
      result shouldEqual ((iriSubject, deprecated, obj(false)))
    }

    "return None when triple not found" in {
      val other = predicate(schema + "other")
      graph.find { case (s, p, _) => s == iriSubject && other == p } shouldEqual None
    }

    "return the root @type fields" in {
      graph.rootTypes shouldEqual Set(schema.Person)
    }

    "return a new Graph with added triples" in {
      graph.add(schema.age, 30).triples shouldEqual graph.triples + ((iriSubject, schema.age, 30))
    }

    "be converted to NTriples" in {
      val expected = contentOf("ntriples.nt", "bnode" -> bnode.rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to NTriples with a root blank node" in {
      val expected = contentOf("ntriples.nt", "bnode" -> bnodeNoId.rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      graphNoId.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to dot without context" in {
      val expected = contentOf("graph/dot-expanded.dot", "bnode" -> bnode.rdfFormat, "rootNode" -> iri.toString)
      graph.toDot().accepted.toString should equalLinesUnordered(expected)
    }

    "be converted to dot without context with a root blank node" in {
      val expected =
        contentOf("graph/dot-expanded.dot", "bnode" -> bnodeNoId.rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      graphNoId.toDot().accepted.toString should equalLinesUnordered(expected)
    }

    "be converted to dot with context" in {
      val expected = contentOf("graph/dot-compacted.dot", "bnode" -> bnode.rdfFormat, "rootNode" -> "john-doé")
      val context  = jsonContentOf("context.json").topContextValueOrEmpty
      graph.toDot(context).accepted.toString should equalLinesUnordered(expected)
    }

    "be converted to dot with context with a root blank node" in {
      val expected =
        contentOf("graph/dot-compacted.dot", "bnode" -> bnodeNoId.rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      val context  = jsonContentOf("context.json").topContextValueOrEmpty
      graphNoId.toDot(context).accepted.toString should equalLinesUnordered(expected)
    }

    // The returned json is not exactly the same as the original expanded json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to expanded JSON-LD" in {
      val expanded = jsonContentOf("graph/expanded.json")
      graph.toExpandedJsonLd.accepted.json shouldEqual expanded
    }

    // The returned json is not exactly the same as the original expanded json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to expanded JSON-LD with a root blank node" in {
      val expanded = jsonContentOf("graph/expanded.json").removeAll(keywords.id -> iri)
      graphNoId.toExpandedJsonLd.accepted.json shouldEqual expanded
    }

    "be converted to compacted JSON-LD from a multiple root" in {
      val expandedJson = jsonContentOf("/graph/expanded-multiple-roots.json")
      val expanded     = ExpandedJsonLd.expanded(expandedJson).rightValue
      val graph        = Graph(expanded).rightValue

      val ctx          =
        ContextObject(json"""{"@vocab": "http://schema.org/", "@base": "http://nexus.example.com/"}""".asObject.value)
      val expectedJson =
        json"""{"@graph": [{"@id": "batman", "@type": "Hero"}, {"@id": "john-doé", "@type": "Person"} ] }"""

      graph.toCompactedJsonLd(ctx).accepted shouldEqual CompactedJsonLd.unsafe(iri, ctx, expectedJson.asObject.value)
    }

    // The returned json is not exactly the same as the original compacted json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to compacted JSON-LD" in {
      val context   = jsonContentOf("context.json").topContextValueOrEmpty
      val compacted = jsonContentOf("graph/compacted.json")
      graph.toCompactedJsonLd(context).accepted.json shouldEqual compacted
    }

    // The returned json is not exactly the same as the original compacted json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to compacted JSON-LD with a root blank node" in {
      val context   = jsonContentOf("context.json").topContextValueOrEmpty
      val compacted = jsonContentOf("graph/compacted.json").removeAll("id" -> "john-doé")
      graphNoId.toCompactedJsonLd(context).accepted.json shouldEqual compacted
    }
  }
}
