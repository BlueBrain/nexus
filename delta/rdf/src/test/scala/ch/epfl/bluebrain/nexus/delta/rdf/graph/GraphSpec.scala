package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.BNode
import ch.epfl.bluebrain.nexus.delta.rdf.RdfError.{ConversionError, UnexpectedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph.rdfType
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdJavaApi, JsonLdOptions}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue.{ContextEmpty, ContextObject}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.rdf.query.SparqlQuery.SparqlConstructQuery
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

    val name      = predicate(schema.name)
    val birthDate = predicate(schema + "birthDate")

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

    "return an object from a found triple" in {
      val deprecated = schema + "deprecated"
      val result     = graph.find(iri, deprecated).value
      result shouldEqual obj(false)
    }

    "return None when triple with given subject and predicate not found" in {
      val other = schema + "other"
      graph.find(iri, other) shouldEqual None
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

    "be created from NTriples" in {
      val ntriplesValue = contentOf("ntriples.nt", "bnode" -> bnode.rdfFormat, "rootNode" -> iri.rdfFormat)
      val result        = Graph(NTriples(ntriplesValue, iri)).rightValue
      val (node, _, _)  = result.find { case (_, p, _) => p == predicate(iri"http://example.com/street") }.value
      val newBNode      = BNode.unsafe(node.getBlankNodeLabel)
      result shouldEqual graph.replace(bnode, newBNode)
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

    "be created from NQuads with a named graph" in {
      val nquadsValue = contentOf("graph/multiple-roots-namedgraph.nq")
      val iriGraph    = iri"http://nexus.example.com/named-graph"
      Graph(NQuads(nquadsValue, iriGraph)).rightValue shouldEqual namedGraph
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

    "return a new graph after running a construct query on it" in {
      val query = SparqlConstructQuery.unsafe("""
          |prefix example: <http://example.com/>
          |prefix schema: <http://schema.org/>
          |
          |CONSTRUCT {
          |  ?person 	        a                       ?type ;
          |                   schema:name             ?name ;
          |                   schema:birthDate        ?birthDate ;
          |} WHERE {
          |  ?person 	        a                       ?type ;
          |         	        schema:name             ?name ;
          |                   schema:birthDate        ?birthDate ;
          |}
          |""".stripMargin)

      graph.transform(query).rightValue shouldEqual graph.filter { case (s, p, _) =>
        s == graph.rootResource && (p == name || p == birthDate || p == rdfType)
      }
    }

    "raise an error for an invalid query" in {
      val query = SparqlConstructQuery.unsafe("""
          |prefix example: <http://example.com/>
          |prefix schema: <http://schema.org/>
          |
          |CONSTRUCT {
          |  ?person 	        a                       ?type ;
          |                   schema:name             ?name ;
          |                   schema:birthDate        ?birthDate ;
          |} WHERE {
          |  fail 	          a                       ?type ;
          |         	        schema:name             ?name ;
          |                   schema:birthDate        ?birthDate ;
          |}
          |""".stripMargin)

      val error = graph.transform(query).leftValue
      error.rootNode shouldEqual error.rootNode
    }

    "raise an error with a strict parser when an iri is invalid" in {
      val expandedJson = jsonContentOf("expanded-invalid-iri.json")
      val expanded     = ExpandedJsonLd.expanded(expandedJson).rightValue
      Graph(expanded).leftValue shouldEqual ConversionError(
        "Bad IRI: < http://nexus.example.com/myid> Spaces are not legal in URIs/IRIs.",
        "toRdf"
      )
    }

    "not raise an error with a lenient parser when an iri is invalid" in {
      val expandedJson = jsonContentOf("expanded-invalid-iri.json")
      val expanded     = ExpandedJsonLd.expanded(expandedJson).rightValue
      Graph(expanded)(JsonLdJavaApi.lenient, JsonLdOptions.defaults).rightValue
    }
  }
}
