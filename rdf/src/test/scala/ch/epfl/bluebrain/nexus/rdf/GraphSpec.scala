package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import ch.epfl.bluebrain.nexus.rdf.Graph.{OptionalGraph, SetGraph, Triple}
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.schema
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import org.scalactic.Equality

class GraphSpec extends RdfSpec {

  implicit val graphEquality: Equality[Graph] = new Equality[Graph] {
    override def areEqual(a: Graph, b: Any): Boolean = b match {
      case bb: Graph => Eq.eqv(a, bb)
      case _         => false
    }
  }

  "A Graph" should {

    val id = url"http://nexus.example.com/id"

    "prepend a triple" when {
      "it's a SingleNodeGraph" in {
        (id: IriOrBNode, schema.name: IriNode) :: Graph(b"1") shouldEqual Graph(id, Set((id, schema.name, b"1")))
      }
      "it's a MultiNodeGraph" in {
        val g = Graph(b"1", Set((b"1", schema.name, b"2")))
        (id: IriOrBNode, schema.name: IriNode) :: g shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (b"1", schema.name, b"2")
          )
        )
      }
      "it's an OptionalGraph with no values" in {
        (id: IriOrBNode, schema.name: IriNode) :: OptionalGraph(None) shouldEqual OptionalGraph(None)
      }
      "it's an OptionalGraph with values" in {
        (id: IriOrBNode, schema.name: IriNode) :: OptionalGraph(Some(Graph(b"1"))) shouldEqual Graph(
          id,
          Set((id, schema.name, b"1"))
        )
      }
      "it's a SetGraph" in {
        val set = SetGraph(b"1", Set(Graph(b"1"), Graph(b"2")))
        (id: IriOrBNode, schema.name: IriNode) :: set shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (id, schema.name, b"2")
          )
        )
      }
    }

    "prepend a graph" when {
      "it's a SingleNodeGraph" in {
        Graph(b"1").prepend(Graph(id), schema.name) shouldEqual Graph(id, Set((id, schema.name, b"1")))
      }
      "it's a MultiNodeGraph" in {
        val g = Graph(b"1", Set((b"1", schema.name, b"2")))
        g.prepend(Graph(id), schema.name) shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (b"1", schema.name, b"2")
          )
        )
      }
      "it's an OptionalGraph with no values" in {
        OptionalGraph(None).prepend(Graph(id), schema.name) shouldEqual Graph(id)
      }
      "it's an OptionalGraph with values" in {
        OptionalGraph(Some(Graph(b"1"))).prepend(Graph(id), schema.name) shouldEqual Graph(
          id,
          Set((id, schema.name, b"1"))
        )
      }
      "it's a SetGraph" in {
        val set = SetGraph(b"1", Set(Graph(b"1"), Graph(b"2")))
        set.prepend(Graph(id), schema.name) shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (id, schema.name, b"2")
          )
        )
      }
    }

    "append a graph" when {
      "it's a SingleNodeGraph" in {
        Graph(id).append(schema.name, Graph(b"1")) shouldEqual Graph(id, Set((id, schema.name, b"1")))
      }
      "it's a MultiNodeGraph" in {
        val g = Graph(id, Set((id, schema.name, b"1")))
        g.append(schema.name, Graph(b"2")) shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (id, schema.name, b"2")
          )
        )
      }
      "it's an OptionalGraph with no values" in {
        OptionalGraph(None).append(schema.name, Graph(id)) shouldEqual Graph(id)
      }
      "it's an OptionalGraph with values" in {
        OptionalGraph(Some(Graph(b"1"))).append(schema.name, Graph(id)) shouldEqual Graph(
          b"1",
          Set((b"1", schema.name, id))
        )
      }
      "it's a SetGraph" in {
        val set = SetGraph(b"1", Set(Graph(b"1"), Graph(b"2")))
        set.append(schema.name, Graph(id)) shouldEqual Graph(
          b"1",
          Set(
            (b"1", schema.name, id)
          )
        )
      }
    }

    "append a triple" when {
      "it's a SingleNodeGraph" in {
        Graph(id).append(schema.name, b"1") shouldEqual Graph(id, Set((id, schema.name, b"1")))
      }
      "it's a MultiNodeGraph" in {
        val g = Graph(id, Set((id, schema.name, b"1")))
        g.append(schema.name, b"2") shouldEqual Graph(
          id,
          Set(
            (id, schema.name, b"1"),
            (id, schema.name, b"2")
          )
        )
      }
      "it's an OptionalGraph with no values" in {
        OptionalGraph(None).append(schema.name, id) shouldEqual OptionalGraph(None)
      }
      "it's an OptionalGraph with values" in {
        OptionalGraph(Some(Graph(b"1"))).append(schema.name, id) shouldEqual Graph(
          b"1",
          Set((b"1", schema.name, id))
        )
      }
      "it's a SetGraph" in {
        val set = SetGraph(b"1", Set(Graph(b"1"), Graph(b"2")))
        set.append(schema.name, id) shouldEqual Graph(
          b"1",
          Set(
            (b"1", schema.name, id)
          )
        )
      }
    }

    "add a triple" in {
      val triple: Triple = (id, schema.name, b"2")
      Graph(b"1") + triple shouldEqual Graph(b"1", Set(triple))
    }

    "add many triples" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      Graph(b"1") ++ triples shouldEqual Graph(b"1", triples)
    }

    "add a graph" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      Graph(b"1") ++ Graph(id, triples) shouldEqual Graph(b"1", triples)
    }

    "remove a triple" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      val triple: Triple = (id, schema.name, b"1")
      Graph(b"1", triples) - triple shouldEqual Graph(b"1", Set((id, schema.name, b"2")))
    }

    "remove no triple if it doesn't exist" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      val triple: Triple = (id, schema.name, b"18237981273")
      Graph(b"1", triples) - triple shouldEqual Graph(b"1", triples)
    }

    "remove many triples" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      val toRemove = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"3")
      )
      Graph(b"1", triples) -- toRemove shouldEqual Graph(b"1", Set((id, schema.name, b"2")))
    }

    "remove a graph" in {
      val triples = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"2")
      )
      val toRemove = Set[Triple](
        (id, schema.name, b"1"),
        (id, schema.name, b"3")
      )
      Graph(b"1", triples) -- Graph(id, toRemove) shouldEqual Graph(b"1", Set((id, schema.name, b"2")))
    }

    "return subjects" in {
      Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.name, b"2")
        )
      ).subjects shouldEqual Set[IriOrBNode](id, b"1")
    }

    "return predicates" in {
      Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2")
        )
      ).predicates shouldEqual Set[IriNode](schema.name, schema.age)
    }

    "return objects" in {
      Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.name, b"2")
        )
      ).objects shouldEqual Set[Node](b"1", b"2")
    }

    "filter triples" in {
      Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2")
        )
      ).filter { case (_, p, _) => p.value == schema.name } shouldEqual Graph(id, Set((id, schema.name, b"1")))
    }

    "replace main node" in {
      OptionalGraph(None).withRoot(id) shouldEqual Graph(id)
      Graph(b"1").withRoot(id) shouldEqual Graph(id)
      SetGraph(b"1", Set(Graph(b"1"))).withRoot(id) shouldEqual SetGraph(id, Set(Graph(b"1")))
    }

    "replace a node" in {
      val g = Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2")
        )
      )
      val set = SetGraph(id, Set(g))
      val opt = OptionalGraph(Some(set))
      opt.replaceNode(b"1", b"0") shouldEqual Graph(
        id,
        Set(
          (id, schema.name, b"0"),
          (b"0", schema.age, b"2")
        )
      )

      opt.replaceNode(id, b"0") shouldEqual Graph(
        b"0",
        Set(
          (b"0", schema.name, b"1"),
          (b"1", schema.age, b"2")
        )
      )
    }

    "fold over the triples" in {
      val g = Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2"),
          (b"2", schema.value, b"3")
        )
      )
      g.foldLeft(Set.empty[BNode]) {
        case (acc, (s, _, o)) =>
          acc ++ s.asBlank.iterator.toSet ++ o.asBlank.iterator.toSet
      } shouldEqual Set(b"1", b"2", b"3")
    }

    "select objects" in {
      val g = Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2"),
          (b"2", schema.value, b"3")
        )
      )
      g.select(b"1", schema.age) shouldEqual Set(b"2")
    }

    "select subjects" in {
      val g = Graph(
        id,
        Set(
          (id, schema.name, b"1"),
          (b"1", schema.age, b"2"),
          (b"2", schema.value, b"3")
        )
      )
      g.selectReverse(b"2", schema.age) shouldEqual Set(b"1")
    }

    "return the correct ntriples representation" in {
      val jDoe  = url"http://nexus.example.com/john-doe"
      val other = url"http://nexus.example.com/other"
      val g = Graph(
        jDoe,
        Set(
          (jDoe, url"http://schema.org/name", "John Doe"),
          (jDoe, url"http://example.com/stringProperty", "Some property"),
          (jDoe, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Person"),
          (jDoe, url"http://schema.org/birthDate", "1999-04-09T20:00Z"),
          (jDoe, url"http://schema.org/deprecated", false),
          (other, url"http://schema.org/birthDate", "2000-04-12T20:00Z"),
          (other, url"http://schema.org/height", 9.223),
          (other, url"http://schema.org/birthYear", 1999),
          (other, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Other"),
          (other, url"http://schema.org/birthHour", 9),
          (jDoe, url"http://example.com/sibling", other)
        )
      )
      val expected =
        """<http://nexus.example.com/other> <http://schema.org/birthDate> "2000-04-12T20:00Z" .
          |<http://nexus.example.com/john-doe> <http://example.com/stringProperty> "Some property" .
          |<http://nexus.example.com/other> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "http://schema.org/Other" .
          |<http://nexus.example.com/john-doe> <http://schema.org/deprecated> "false"^^<http://www.w3.org/2001/XMLSchema#boolean> .
          |<http://nexus.example.com/other> <http://schema.org/height> "9.223"^^<http://www.w3.org/2001/XMLSchema#double> .
          |<http://nexus.example.com/john-doe> <http://example.com/sibling> <http://nexus.example.com/other> .
          |<http://nexus.example.com/john-doe> <http://schema.org/name> "John Doe" .
          |<http://nexus.example.com/john-doe> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> "http://schema.org/Person" .
          |<http://nexus.example.com/other> <http://schema.org/birthHour> "9"^^<http://www.w3.org/2001/XMLSchema#integer> .
          |<http://nexus.example.com/john-doe> <http://schema.org/birthDate> "1999-04-09T20:00Z" .
          |<http://nexus.example.com/other> <http://schema.org/birthYear> "1999"^^<http://www.w3.org/2001/XMLSchema#integer> .
          |""".stripMargin

      g.ntriples shouldEqual expected
    }

    "return the correct DOT representation" in {
      val jDoe  = url"http://nexus.example.com/john-doe"
      val other = url"http://nexus.example.com/other"
      val graph = Graph(
        jDoe,
        Set(
          (jDoe, url"http://schema.org/name", "John Doe"),
          (jDoe, url"http://example.com/stringProperty", "Some property"),
          (jDoe, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Person"),
          (jDoe, url"http://schema.org/birthDate", "1999-04-09T20:00Z"),
          (jDoe, url"http://schema.org/deprecated", false),
          (other, url"http://schema.org/birthDate", "2000-04-12T20:00Z"),
          (other, url"http://schema.org/height", 9.223),
          (other, url"http://schema.org/birthYear", 1999),
          (other, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Other"),
          (other, url"http://schema.org/birthHour", 9),
          (jDoe, url"http://example.com/sibling", other)
        )
      )
      val expected =
        """digraph "http://nexus.example.com/john-doe" {
            |  "http://nexus.example.com/john-doe" -> "John Doe" [label = "http://schema.org/name"]
            |  "http://nexus.example.com/john-doe" -> "Some property" [label = "http://example.com/stringProperty"]
            |  "http://nexus.example.com/john-doe" -> "http://schema.org/Person" [label = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
            |  "http://nexus.example.com/john-doe" -> "1999-04-09T20:00Z" [label = "http://schema.org/birthDate"]
            |  "http://nexus.example.com/john-doe" -> false [label = "http://schema.org/deprecated"]
            |  "http://nexus.example.com/other" -> "2000-04-12T20:00Z" [label = "http://schema.org/birthDate"]
            |  "http://nexus.example.com/other" -> 9.223 [label = "http://schema.org/height"]
            |  "http://nexus.example.com/other" -> 1999 [label = "http://schema.org/birthYear"]
            |  "http://nexus.example.com/other" -> "http://schema.org/Other" [label = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
            |  "http://nexus.example.com/other" -> 9 [label = "http://schema.org/birthHour"]
            |  "http://nexus.example.com/john-doe" -> "http://nexus.example.com/other" [label = "http://example.com/sibling"]
            |}""".stripMargin
      graph.dot().split("\n").sorted shouldEqual expected.split("\n").sorted
    }

    "return the correct DOT representation with prefix mappings" in {
      val jDoe  = url"http://nexus.example.com/john-doe"
      val other = url"http://nexus.example.com/other"
      val mappings: Map[AbsoluteIri, String] = Map(
        url"http://schema.org/deprecated"                    -> "deprecated",
        url"http://schema.org/Person"                        -> "Person",
        url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type" -> "@type",
        url"http://schema.org/"                              -> "schema"
      )
      val graph = Graph(
        jDoe,
        Set(
          (jDoe, url"http://schema.org/name", "John Doe"),
          (jDoe, url"http://example.com/stringProperty", "Some property"),
          (jDoe, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", url"http://schema.org/Person"),
          (jDoe, url"http://schema.org/birthDate", "1999-04-09T20:00Z"),
          (jDoe, url"http://schema.org/deprecated", false),
          (other, url"http://schema.org/birthDate", "2000-04-12T20:00Z"),
          (other, url"http://schema.org/height", 9.223),
          (other, url"http://schema.org/birthYear", 1999),
          (other, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Other"),
          (other, url"http://schema.org/birthHour", 9),
          (jDoe, url"http://example.com/sibling", other)
        )
      )
      val expected =
        """digraph "http://nexus.example.com/john-doe" {
          |  "http://nexus.example.com/john-doe" -> "John Doe" [label = "schema:name"]
          |  "http://nexus.example.com/john-doe" -> "Some property" [label = "http://example.com/stringProperty"]
          |  "http://nexus.example.com/john-doe" -> Person [label = "@type"]
          |  "http://nexus.example.com/john-doe" -> "1999-04-09T20:00Z" [label = "schema:birthDate"]
          |  "http://nexus.example.com/john-doe" -> false [label = deprecated]
          |  "http://nexus.example.com/other" -> "2000-04-12T20:00Z" [label = "schema:birthDate"]
          |  "http://nexus.example.com/other" -> 9.223 [label = "schema:height"]
          |  "http://nexus.example.com/other" -> 1999 [label = "schema:birthYear"]
          |  "http://nexus.example.com/other" -> "http://schema.org/Other" [label = "@type"]
          |  "http://nexus.example.com/other" -> 9 [label = "schema:birthHour"]
          |  "http://nexus.example.com/john-doe" -> "http://nexus.example.com/other" [label = "http://example.com/sibling"]
          |}""".stripMargin

      graph.dot(prefixMappings = mappings).split("\n").sorted shouldEqual expected.split("\n").sorted
    }

    "return the correct DOT representation with sequential blank node ids" in {
      val jDoe   = url"http://nexus.example.com/john-doe"
      val other  = BNode()
      val other2 = BNode()
      val graph = Graph(
        jDoe,
        Set(
          (jDoe, url"http://schema.org/name", "John Doe"),
          (jDoe, url"http://example.com/stringProperty", "Some property"),
          (jDoe, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Person"),
          (jDoe, url"http://schema.org/birthDate", "1999-04-09T20:00Z"),
          (jDoe, url"http://schema.org/deprecated", false),
          (other, url"http://schema.org/birthDate", "2000-04-12T20:00Z"),
          (other, url"http://schema.org/height", 9.223),
          (other, url"http://schema.org/birthYear", 1999),
          (other, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", "http://schema.org/Other"),
          (other, url"http://schema.org/birthHour", 9),
          (jDoe, url"http://example.com/sibling", other),
          (other, url"http://example.com/sibling", other2)
        )
      )
      val expected =
        """digraph "http://nexus.example.com/john-doe" {
          |  "http://nexus.example.com/john-doe" -> "John Doe" [label = "http://schema.org/name"]
          |  "http://nexus.example.com/john-doe" -> "Some property" [label = "http://example.com/stringProperty"]
          |  "http://nexus.example.com/john-doe" -> "http://schema.org/Person" [label = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
          |  "http://nexus.example.com/john-doe" -> "1999-04-09T20:00Z" [label = "http://schema.org/birthDate"]
          |  "http://nexus.example.com/john-doe" -> false [label = "http://schema.org/deprecated"]
          |  "_:b1" -> "2000-04-12T20:00Z" [label = "http://schema.org/birthDate"]
          |  "_:b1" -> 9.223 [label = "http://schema.org/height"]
          |  "_:b1" -> 1999 [label = "http://schema.org/birthYear"]
          |  "_:b1" -> "http://schema.org/Other" [label = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type"]
          |  "_:b1" -> 9 [label = "http://schema.org/birthHour"]
          |  "http://nexus.example.com/john-doe" -> "_:b1" [label = "http://example.com/sibling"]
          |  "_:b1" -> "_:b2" [label = "http://example.com/sibling"]
          |}""".stripMargin
      graph.dot().split("\n").sorted shouldEqual expected.split("\n").sorted
    }

    "return the correct DOT representation with prefix mappings and shortened URLs" in {
      val jDoe  = url"http://nexus.example.com/john-doe"
      val other = url"http://nexus.example.com/other/another"
      val mappings: Map[AbsoluteIri, String] = Map(
        url"http://schema.org/deprecated" -> "deprecated",
        url"http://schema.org/Person"     -> "Person",
        url"http://schema.org/"           -> "schema"
      )
      val graph = Graph(
        jDoe,
        Set(
          (jDoe, url"http://schema.org/name", "John Doe"),
          (jDoe, url"http://example.com/stringProperty", "Some property"),
          (jDoe, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", url"http://schema.org/Person"),
          (jDoe, url"http://schema.org/birthDate", "1999-04-09T20:00Z"),
          (jDoe, url"http://schema.org/deprecated", false),
          (other, url"http://schema.org/birthDate", "2000-04-12T20:00Z"),
          (other, url"http://schema.org/height", 9.223),
          (other, url"http://schema.org/birthYear", 1999),
          (other, url"http://www.w3.org/1999/02/22-rdf-syntax-ns#type", url"http://schema.org/Other"),
          (other, url"http://schema.org/birthHour", 9),
          (jDoe, url"http://example.com/sibling", other)
        )
      )
      val expected =
        """digraph "john-doe" {
          |  "john-doe" -> "John Doe" [label = "schema:name"]
          |  "john-doe" -> "Some property" [label = stringProperty]
          |  "john-doe" -> Person [label = type]
          |  "john-doe" -> "1999-04-09T20:00Z" [label = "schema:birthDate"]
          |  "john-doe" -> false [label = deprecated]
          |  another -> "2000-04-12T20:00Z" [label = "schema:birthDate"]
          |  another -> 9.223 [label = "schema:height"]
          |  another -> 1999 [label = "schema:birthYear"]
          |  another -> "schema:Other" [label = type]
          |  another -> 9 [label = "schema:birthHour"]
          |  "john-doe" -> another [label = sibling]
          |}""".stripMargin
      graph.dot(prefixMappings = mappings, stripPrefixes = true).split("\n").sorted shouldEqual expected
        .split("\n")
        .sorted
    }

  }
}
