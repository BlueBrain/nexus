package ch.epfl.bluebrain.nexus.cli

import ch.epfl.bluebrain.nexus.cli.types.SparqlResults
import ch.epfl.bluebrain.nexus.cli.types.SparqlResults.{Binding, Bindings, Head, Literal}
import ch.epfl.bluebrain.nexus.cli.utils.{Fixtures, Resources}
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SparqlResultsSpec extends AnyWordSpecLike with Matchers with Resources with Fixtures {

  "A SparqlResult" should {
    val json    = jsonContentOf("/sparql_results.json")
    val askJson = jsonContentOf("/sparql_results_ask.json")
    val subject = "https://example.com/subject"

    val map1 = Map(
      "s" -> Binding("uri", subject),
      "p" -> Binding("uri", (nxv / "constrainedBy").toString()),
      "o" -> Binding("uri", "https://bluebrain.github.io/nexus/schemas/resolver.json")
    )

    val map2 = Map(
      "s" -> Binding("uri", subject),
      "p" -> Binding("uri", (nxv / "deprecated").toString()),
      "o" -> Binding("literal", "false", datatype = Some("http://www.w3.org/2001/XMLSchema#boolean"))
    )

    val map3 = Map(
      "s" -> Binding("uri", subject),
      "p" -> Binding("uri", (nxv / "label").toString()),
      "o" -> Binding("literal", "myText")
    )

    val head = Head(List("s", "p", "o"))

    val qr    = SparqlResults(head, Bindings(map1, map2, map3))
    val qrAsk = SparqlResults(Head(), Bindings(), Some(true))

    "be decoded" in {
      json.as[SparqlResults] shouldEqual Right(qr)
      askJson.as[SparqlResults] shouldEqual Right(qrAsk)
    }

    "add head" in {
      head ++ Head(List("v", "hpage", "s")) shouldEqual Head(List("s", "p", "o", "v", "hpage"))
    }

    "add binding" in {
      (Bindings(map1) ++ Bindings(map2) ++ Bindings(map3)) shouldEqual qr.results
    }

    "add results" in {
      SparqlResults(head, Bindings(map1) ++ Bindings(map2)) ++ SparqlResults(head, Bindings(map3)) shouldEqual qr
    }

    "convert bindings" in {
      map3.values.toList match {
        case first :: second :: third :: Nil =>
          first.asUri.value shouldEqual Uri.unsafeFromString(subject)
          second.asUri.value shouldEqual nxv / "label"
          third.asLiteral.value shouldEqual Literal("myText", SparqlResults.rdfString)
          third.asBNode shouldEqual None
          third.asUri shouldEqual None
        case _ => fail()
      }
    }

  }
}
