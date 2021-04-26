package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.UnexpectedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.{ContextEmpty, ContextObject}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GraphSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A Graph" should {
    val expandedJson     = jsonContentOf("expanded.json")
    val expanded         = ExpandedJsonLd.expanded(expandedJson).rightValue
    val nquads           = NQuads(contentOf("nquads.nq"), iri)
    val graph            = Graph(expanded).rightValue
    val bnode            = bNode(graph)
    val iriSubject       = subject(iri)
    val rootBNode        = BNode.random
    val expandedNoIdJson = expandedJson.removeAll(keywords.id -> iri)
    val expandedNoId     = ExpandedJsonLd.expanded(expandedNoIdJson).rightValue.replaceId(rootBNode)
    val graphNoId        = Graph(expandedNoId).rightValue
    val bnodeNoId        = bNode(graphNoId)
    val namedGraph       = Graph(
      ExpandedJsonLd.expanded(jsonContentOf("/graph/expanded-multiple-roots-namedgraph.json")).rightValue
    ).rightValue

    "be created from expanded jsonld" in {
      Graph(expanded).rightValue.triples.size shouldEqual 16
    }

    "be created from n-quads" in {
      Graph(nquads).rightValue.triples.size shouldEqual 16
    }

    "be created from expanded jsonld with a root blank node" in {
      Graph(expandedNoId).rightValue.triples.size shouldEqual 16
    }

    "replace its root node" in {
      val iri2     = iri"http://example.com/newid"
      val subject2 = subject(iri2)
      val graph    = Graph(expanded).rightValue
      val graph2   = graph.replaceRootNode(iri2)
      val expected = graph.triples.map { case (s, p, o) => (if (s == iriSubject) subject2 else s, p, o) }
      graph2.rootNode shouldEqual iri2
      graph2.triples shouldEqual expected
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

    "be converted to NTriples from a named graph" in {
      val expected = contentOf("graph/multiple-roots-namedgraph.nt")
      namedGraph.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to NTriples with a root blank node" in {
      val expected = contentOf("ntriples.nt", "bnode" -> bnodeNoId.rdfFormat, "rootNode" -> rootBNode.rdfFormat)
      graphNoId.toNTriples.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to NQuads" in {
      val expected = contentOf("ntriples.nt", "bnode" -> bnode.rdfFormat, "rootNode" -> iri.rdfFormat)
      graph.toNQuads.rightValue.toString should equalLinesUnordered(expected)
    }

    "be converted to NQuads from a named graph" in {
      val expected = contentOf("graph/multiple-roots-namedgraph.nq")
      namedGraph.toNQuads.rightValue.toString should equalLinesUnordered(expected)
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

    "failed to be converted to compacted JSON-LD from a multiple root" in {
      val expandedJson = jsonContentOf("/graph/expanded-multiple-roots.json")
      val expanded     = ExpandedJsonLd(expandedJson).accepted
      Graph(expanded).leftValue shouldEqual UnexpectedJsonLd("Expected named graph, but root @id not found")
    }

    "be converted to compacted JSON-LD from a named graph" in {

      val ctx =
        ContextObject(jobj"""{"@vocab": "http://schema.org/", "@base": "http://nexus.example.com/"}""")

      namedGraph.toCompactedJsonLd(ctx).accepted shouldEqual
        CompactedJsonLd.unsafe(
          iri"http://nexus.example.com/named-graph",
          ctx,
          jsonObjectContentOf("jsonld/graph/compacted-multiple-roots-namedgraph.json")
        )
    }

    "be converted to compacted JSON-LD from an empty graph" in {
      Graph.empty.toCompactedJsonLd(ContextEmpty).accepted.json shouldEqual json"""{}"""
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
