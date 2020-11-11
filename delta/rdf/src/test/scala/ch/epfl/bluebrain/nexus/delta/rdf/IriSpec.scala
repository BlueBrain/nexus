package ch.epfl.bluebrain.nexus.delta.rdf

import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{owl, schema, xsd}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.Json
import io.circe.syntax._

class IriSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValuable {

  "An Iri" should {
    val iriString = "http://example.com/a"
    val iri       = iri"$iriString"

    "fail to construct" in {
      Iri.absolute("abc").leftValue
      Iri.apply("a:*#").leftValue
    }

    "be empty" in {
      iri"".isEmpty shouldEqual true
    }

    "not be empty" in {
      iri.nonEmpty shouldEqual true
    }

    "be absolute" in {
      iri.isAbsolute shouldEqual true
    }

    "be a prefix mapping" in {
      forAll(List(schema.base, xsd.base, owl.base)) { iri =>
        iri.isPrefixMapping shouldEqual true
      }
    }

    "not be a prefix mapping" in {
      forAll(List(schema.Person, xsd.int, owl.oneOf)) { iri =>
        iri.isPrefixMapping shouldEqual false
      }
    }

    "strip prefix" in {
      schema.Person.stripPrefix(schema.base) shouldEqual "Person"
      xsd.integer.stripPrefix(xsd.base) shouldEqual "integer"
    }

    "append segment" in {
      val list     = List(
        iri"http://example.com/a"  -> "b",
        iri"http://example.com/a/" -> "/b",
        iri"http://example.com/a/" -> "b",
        iri"http://example.com/a"  -> "/b"
      )
      val expected = iri"http://example.com/a/b"
      forAll(list) { case (iri, segment) => (iri / segment) shouldEqual expected }
    }

    "extract its query parameters" in {
      val list = List(
        iri"http://example.com?"            -> Query.Empty,
        iri"http://example.com?a=1&b=2&b=3" -> Query.Empty.+:("b" -> "3").+:("b" -> "2").+:("a" -> "1"),
        iri"http://example.com?a"           -> Query.Empty.+:("a" -> "")
      )
      forAll(list) { case (iri, qp) => iri.query() shouldEqual qp }
    }

    "remove query param field" in {
      val list = List(
        iri"http://example.com?"                                   -> iri"http://example.com?",
        iri"http://example.com?a=1&c=2"                            -> iri"http://example.com?a=1&c=2",
        iri"http://example.com?b=1&b=2"                            -> iri"http://example.com",
        iri"http://example.com?b=1&b=2&a=1"                        -> iri"http://example.com?a=1",
        iri"http://user:pass@example.com?d=1b&b&ab=1&b=2&b=3#frag" -> iri"http://user:pass@example.com?ab=1#frag"
      )
      forAll(list) { case (iri, afterRemoval) =>
        iri.removeQueryParams("b", "d") shouldEqual afterRemoval
      }

    }

    "be converted to Json" in {
      iri.asJson shouldEqual Json.fromString(iriString)
    }

    "be constructed from Json" in {
      Json.fromString(iriString).as[Iri].rightValue shouldEqual iri
    }
  }

}
