package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Curie._
import io.circe.Json
import io.circe.syntax._

class CurieSpec extends RdfSpec {
  "A Prefix" should {

    val valid = List(
      "prefix",
      "PrEfIx",
      "_prefix",
      "__prefix",
      "_.prefix",
      "pre-fix",
      "_123.456",
      "Àprefix",
      "Öfix",
      "-prefix",
      "!prefix",
      ".prefix",
      "6prefix",
      "prefi!x",
      "@",
      "@foo.bar"
    )
    val invalid = List(":prefix", "prefix:", "", "@prefix")

    "be parsed correctly from string" in {
      forAll(valid)(in => Prefix(in).rightValue.value shouldEqual in)
    }

    "fail parsing from an invalid string" in {
      forAll(invalid)(in => Prefix(in).leftValue should not be empty)
    }

    "show" in {
      forAll(valid)(in => Prefix(in).rightValue.show shouldEqual in)
    }

    "eq" in {
      val lhs = Prefix("prefix").rightValue
      val rhs = Prefix("prefix").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }

  "A Curie" should {
    val valid   = List(("rdf:type", "rdf", "type"), ("rdf:some/other/suffix", "rdf", "some/other/suffix"))
    val invalid = List("prefix://suffix", "", "@prefix:suffix", "prefix:suf:fix")

    "be parsed correctly from string" in {
      forAll(valid) {
        case (in, p, r) =>
          val curie = Curie(in).rightValue
          curie.prefix.value shouldEqual p
          curie.reference shouldEqual r
          curie.show shouldEqual s"$p:${curie.reference}"
      }
    }

    "fail parsing from an invalid string" in {
      forAll(invalid)(in => Curie(in).leftValue should not be empty)
    }

    "eq" in {
      val lhs = Curie("rdf:type").rightValue
      val rhs = Curie("rdf:type").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "not eq" in {
      val lhs = Curie("rdf:type").rightValue
      val rhs = Curie("RdF:type").rightValue
      Eq.eqv(lhs, rhs) shouldEqual false
    }

    "fail to convert to iri when resolved curie is not a Uei" in {
      val c   = Curie("rdf:type?a=b").rightValue
      val iri = Iri.uri("http://example.com/b?c=d").rightValue
      c.toIri(iri).leftValue
    }

    "convert to iri with prefix map" in {
      val c   = Curie("rdf:type").rightValue
      val iri = Iri.uri("http://example.com/a/").rightValue
      val map = Map(Prefix("rdf").rightValue -> iri)
      c.toIri(map).rightValue.value shouldEqual Iri.uri("http://example.com/a/type").rightValue
    }
    "encode" in {
      forAll(valid) {
        case (string, prefix, ref) => Curie(string).rightValue.asJson shouldEqual Json.fromString(s"$prefix:$ref")
      }
    }
    "decode" in {
      forAll(valid) {
        case (string, prefix, ref) =>
          Json.fromString(s"$prefix:$ref").as[Curie].rightValue shouldEqual Curie(string).rightValue
      }
    }
  }
}
