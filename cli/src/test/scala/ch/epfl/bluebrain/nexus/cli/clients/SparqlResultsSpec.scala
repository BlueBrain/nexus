package ch.epfl.bluebrain.nexus.cli.clients

import ch.epfl.bluebrain.nexus.cli.clients.SparqlResults.{Binding, Bindings, Head}
import ch.epfl.bluebrain.nexus.cli.utils.Resources
import org.http4s.Uri
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SparqlResultsSpec extends AnyWordSpecLike with Matchers with Resources {

  "A Sparql Json result" should {
    val json    = jsonContentOf("/templates/sparql-results.json")
    val askJson = jsonContentOf("/templates/sparql-results-ask.json")

    val constrainedBy = Map(
      "s" -> Binding("uri", "https://example.com/subject"),
      "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/constrainedBy"),
      "o" -> Binding("uri", "https://bluebrain.github.io/nexus/schemas/resolver.json")
    )

    val deprecated = Map(
      "s" -> Binding("uri", "https://example.com/subject"),
      "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/deprecated"),
      "o" -> Binding(`type` = "literal", value = "false", datatype = Some("http://www.w3.org/2001/XMLSchema#boolean"))
    )

    val label = Map(
      "s" -> Binding("uri", "https://example.com/subject"),
      "p" -> Binding("uri", "https://bluebrain.github.io/nexus/vocabulary/label"),
      "o" -> Binding(`type` = "literal", value = "myText")
    )

    val head = Head(List("s", "p", "o"))

    val qr    = SparqlResults(head, Bindings(constrainedBy, deprecated, label))
    val qrAsk = SparqlResults(Head(), Bindings(), Some(true))

    "be decoded" in {
      json.as[SparqlResults] shouldEqual Right(qr)
      askJson.as[SparqlResults] shouldEqual Right(qrAsk)
    }

    "add head" in {
      val link = Uri.unsafeFromString("http://www.w3.org/TR/rdf-sparql-XMLres/example.rq")
      head ++ Head(List("s", "p", "name"), Some(List(link))) shouldEqual
        Head(List("s", "p", "o", "name"), Some(List(link)))
    }

    "add binding" in {
      (Bindings(constrainedBy) ++ Bindings(deprecated) ++ Bindings(label)) shouldEqual qr.results
    }
  }
}
