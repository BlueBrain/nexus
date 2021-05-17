package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client.SparqlResults._
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, EitherValuable, TestHelpers}
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SparqlResultsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with CirceEq
    with OptionValues {

  "A Sparql Json result" should {
    val json     = jsonContentOf("sparql/results/query-result.json")
    val askJson  = jsonContentOf("sparql/results/ask-result.json")
    val askJson2 = jsonContentOf("sparql/results/ask-result-2.json")

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
  }
}
