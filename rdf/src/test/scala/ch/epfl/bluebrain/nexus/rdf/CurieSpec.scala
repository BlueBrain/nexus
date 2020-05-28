package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Curie.Prefix

class CurieSpec extends RdfSpec {
  "A Prefix" should {

    val valid   = List("prefix", "PrEfIx", "_prefix", "__prefix", "_.prefix", "pre-fix", "_123.456", "Àprefix", "Öfix")
    val invalid = List("-prefix", "!prefix", ":prefix", ".prefix", "6prefix", "prefi!x", "prefix:", "")

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
    val valid = List(
      ("rdf:type", "rdf", "type"),
      ("prefix://me:me@hOst:443/a/b?a&e=f&b=c#frag", "prefix", "//me:me@host:443/a/b?a&b=c&e=f#frag"),
      ("PrEfIx://me:me@hOst#frag", "PrEfIx", "//me:me@host#frag"),
      ("_prefix:/some/:/path", "_prefix", "/some/:/path"),
      ("_.prefix:/:/some/path", "_.prefix", "/:/some/path"),
      ("pre-fix:some/:/path", "pre-fix", "some/:/path"),
      ("_123.456:?q=v", "_123.456", "?q=v"),
      ("Àprefix:#frag", "Àprefix", "#frag"),
      ("Öfix://hOst%C2%A3:80/a%C2%A3/b%C3%86c//:://", "Öfix", "//host£:80/a£/bÆc//:://")
    )
    val invalid = List(
      "-prefix",
      "!prefix",
      ":prefix",
      ".prefix",
      "6prefix",
      "prefi!x",
      "prefix:",
      "//hOst%C2%A3:80/a%C2%A3/b%C3%86c//:://",
      "?q=v",
      ""
    )

    "be parsed correctly from string" in {
      forAll(valid) {
        case (in, p, r) =>
          val curie = Curie(in).rightValue
          curie.prefix.value shouldEqual p
          curie.reference.asString shouldEqual r
          curie.show shouldEqual s"$p:${curie.reference.asString}"
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

    "fail to convert to iri when not found on prefix mappings" in {
      val c      = Curie("rdf:type").rightValue
      val iri    = Iri.absolute("http://example.com/a").rightValue
      val prefix = Prefix("a").rightValue
      c.toIri(Map(prefix                   -> iri)).leftValue
      c.toIriUnsafePrefix(Map(prefix.value -> iri)).leftValue
    }

    "fail to convert to iri when resolved curie is not an AbsoluteIri" in {
      val c   = Curie("rdf:type?a=b").rightValue
      val iri = Iri.absolute("http://example.com/b?c=d").rightValue
      c.toIri(iri).leftValue
    }

    "convert to iri with prefix map" in {
      val c   = Curie("rdf:type").rightValue
      val iri = Iri.absolute("http://example.com/a/").rightValue
      val map = Map(Prefix("rdf").rightValue -> iri)
      c.toIri(map).rightValue shouldEqual Iri.absolute("http://example.com/a/type").rightValue
    }

    "convert to iri with string prefix map" in {
      val c   = Curie("rdf:type?a=b").rightValue
      val iri = Iri.absolute("http://example.com/a").rightValue
      val map = Map("rdf" -> iri)
      c.toIriUnsafePrefix(map).rightValue shouldEqual Iri.absolute("http://example.com/atype?a=b").rightValue
    }
  }
}
