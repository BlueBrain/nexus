package ch.epfl.bluebrain.nexus.delta.rdf.graph

import ch.epfl.bluebrain.nexus.delta.rdf.Fixtures
import ch.epfl.bluebrain.nexus.delta.rdf.Triple._
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.TestMatchers
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class GraphSpec extends AnyWordSpecLike with Matchers with Fixtures with TestMatchers {

  "A Graph" should {
    val expanded   = jsonContentOf("expanded.json")
    val graph      = Graph(iri, expanded).accepted
    val bnode      = graph
      .find { case (s, p, _) => s == graph.rootResource && p == predicate(vocab + "address") }
      .map(_._3.asNode().getBlankNodeLabel)
      .value
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

    "convert graph to NTriples" in {
      val expected = contentOf("graph/ntriples.nt", "{bnode}" -> s"_:B$bnode")
      graph.toNTriples.accepted.toString should equalLinesUnordered(expected)
    }

    "convert graph to DOT without context" in {
      val expected = contentOf("graph/dot-expanded.dot", "{bnode}" -> s"_:B$bnode")
      graph.toDot.accepted.toString should equalLinesUnordered(expected)
    }

    "convert graph to dot with context" in {
      val expected = contentOf("graph/dot-compacted.dot", "{bnode}" -> s"_:B$bnode")
      val context  = jsonContentOf("context.json")
      graph.toDot(context).accepted.toString should equalLinesUnordered(expected)
    }
  }
}
