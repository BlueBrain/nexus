package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextFields
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GraphSpec extends AnyWordSpecLike with Matchers with Fixtures {

  "A Graph" should {
    val expanded   = jsonContentOf("expanded.json")
    val graph      = Graph(iri, expanded).accepted
    val bnode      = bNode(graph)
    val iriSubject = subject(iri)

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
      val expected = contentOf("ntriples.nt", "{bnode}" -> s"_:B$bnode")
      graph.toNTriples.accepted.toString should equalLinesUnordered(expected)
    }

    "be converted to dot without context" in {
      val expected = contentOf("graph/dot-expanded.dot", "{bnode}" -> s"_:B$bnode")
      graph.toDot().accepted.toString should equalLinesUnordered(expected)
    }

    "be converted to dot with context" in {
      val expected = contentOf("graph/dot-compacted.dot", "{bnode}" -> s"_:B$bnode")
      val context  = jsonContentOf("context.json")
      graph.toDot(context).accepted.toString should equalLinesUnordered(expected)
    }

    // The returned json is not exactly the same as the original expanded json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to expanded JSON-LD" in {
      val expanded = jsonContentOf("graph/expanded.json")
      graph.toExpandedJsonLd.accepted.json shouldEqual expanded
    }

    // The returned json is not exactly the same as the original compacted json from where the Graph was created.
    // This is expected due to the useNativeTypes field on JsonLdOptions and to the @context we have set in place
    "be converted to compacted JSON-LD" in {
      val context   = jsonContentOf("context.json")
      val compacted = jsonContentOf("graph/compacted.json")
      graph.toCompactedJsonLd(context, ContextFields.Skip).accepted.json shouldEqual compacted
    }
  }
}
