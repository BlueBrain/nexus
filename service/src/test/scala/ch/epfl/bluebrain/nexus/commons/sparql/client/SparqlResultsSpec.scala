package ch.epfl.bluebrain.nexus.commons.sparql.client

import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResults.{Binding, Bindings, Head}
import ch.epfl.bluebrain.nexus.commons.sparql.client.SparqlResultsSpec.{nxv, xsd}
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import ch.epfl.bluebrain.nexus.util.{CirceEq, EitherValues, Resources}
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SparqlResultsSpec
    extends AnyWordSpecLike
    with Matchers
    with Resources
    with EitherValues
    with CirceEq
    with OptionValues {
  "A Sparql Json result" should {
    val json          = jsonContentOf("/commons/sparql/results/query-result.json")
    val constructJson = jsonContentOf("/commons/sparql/results/construct-result.json")
    val askJson       = jsonContentOf("/commons/sparql/results/ask-result.json")
    val askJson2      = jsonContentOf("/commons/sparql/results/ask-result-2.json")

    val blurb = Binding(
      "literal",
      "<p xmlns=\"http://www.w3.org/1999/xhtml\">My name is <b>alice</b></p>",
      None,
      Some("http://www.w3.org/1999/02/22-rdf-syntax-ns#XMLLiteral")
    )

    val map1 = Map(
      "x"      -> Binding("bnode", "r1"),
      "hpage"  -> Binding("uri", "http://work.example.org/alice/"),
      "name"   -> Binding("literal", "Alice"),
      "mbox"   -> Binding("literal", ""),
      "blurb"  -> blurb,
      "friend" -> Binding("bnode", "r2")
    )

    val map2 = Map(
      "x"      -> Binding("bnode", "r2"),
      "hpage"  -> Binding("uri", "http://work.example.org/bob/"),
      "name"   -> Binding("literal", "Bob", Some("en")),
      "mbox"   -> Binding("uri", "mailto:bob@work.example.org"),
      "friend" -> Binding("bnode", "r1")
    )

    val head = Head(
      List("x", "hpage", "name", "mbox", "age", "blurb", "friend"),
      Some(List("http://www.w3.org/TR/rdf-sparql-XMLres/example.rq"))
    )

    val qr    = SparqlResults(head, Bindings(map1, map2))
    val qrAsk = SparqlResults(Head(), Bindings(), Some(true))

    "be encoded" in {
      qr.asJson should equalIgnoreArrayOrder(json)
      qrAsk.asJson should equalIgnoreArrayOrder(askJson2)
    }

    "be decoded" in {
      json.as[SparqlResults].rightValue shouldEqual qr
      askJson.as[SparqlResults].rightValue shouldEqual qrAsk
      askJson2.as[SparqlResults].rightValue shouldEqual qrAsk
    }

    "add head" in {
      head ++ Head(List("v", "hpage", "name")) shouldEqual Head(
        List("x", "hpage", "name", "mbox", "age", "blurb", "friend", "v"),
        Some(List("http://www.w3.org/TR/rdf-sparql-XMLres/example.rq"))
      )

      (Head(List("v", "hpage", "name"), Some(List("http://example.com/b"))) ++ Head(
        List("x", "hpage", "name"),
        Some(List("http://example.com/a"))
      )) shouldEqual
        Head(List("v", "hpage", "name", "x"), Some(List("http://example.com/b", "http://example.com/a")))
    }

    "add binding" in {
      (Bindings(map1) ++ Bindings(map2)) shouldEqual qr.results
    }

    "be converted to graph" in {
      val result      = constructJson.as[SparqlResults].rightValue
      val id: IriNode = url"http://example.com/id"
      result.asGraph.value.triples shouldEqual
        Set[Triple](
          (id, nxv + "bnode", Node.blank("t96").rightValue),
          (id, nxv + "deprecated", Node.literal(false)),
          (id, nxv + "deprecated", Node.literal(false)),
          (id, nxv + "createdAt", Node.literal("2019-08-16T12:57:00.532Z", xsd.dateTime)),
          (id, nxv + "project", Node.literal("myproject"))
        )
    }
  }
}

object SparqlResultsSpec {
  object nxv {
    private val base = url"https://bluebrain.github.io/nexus/vocabulary/"

    def +(value: String): IriNode = IriNode(base + value)
  }
  object xsd {
    private val base = url"http://www.w3.org/2001/XMLSchema#"
    val dateTime     = url"${base}dateTime"
  }
}
