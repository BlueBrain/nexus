package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._

class UrnSpec extends RdfSpec {

  "An Urn" should {
    "be parsed correctly" in {
      // format: off
      val cases = List(
        "urn:uUid:6e8bc430-9c3a-11d9-9669-0800200c9a66"           -> "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66",
        "urn:example:a%C2%A3/b%C3%86c//:://?=a=b#"                -> "urn:example:a£/bÆc//:://?=a=b#",
        "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"      -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE",
        "urn:Example:weather?=op=map&lat=39.56&lon=-104.85#test"  -> "urn:example:weather?=op=map&lat=39.56&lon=-104.85#test",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk"           -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk",
        "urn:examp-lE:foo-bar-baz-qux?=a=b?+CCResolve:cc=uk"      -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b"      -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b",
        "urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash" -> "urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash"
      )
      // format: on
      forAll(cases) {
        case (in, expected) =>
          Urn(in).rightValue.iriString shouldEqual expected
      }
    }

    "fail to parse" in {
      val fail = List(
        "urn:example:some/path/?+",
        "urn:example:some/path/?="
      )
      forAll(fail) { str =>
        Urn(str).leftValue
      }
    }

    val withHash = Iri.uri("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").rightValue

    "be a Uri" in {
      withHash.isUri shouldEqual true
    }

    "be a Urn" in {
      withHash.isUrn shouldEqual true
    }

    "as uri" in {
      val iri = Iri.uri("urn:example:a£/bÆc//:://?=a=b#").rightValue
      iri.uriString shouldEqual "urn:example:a%C2%A3/b%C3%86c//:://?=a=b#"
    }

    "show" in {
      val iri = Iri.uri("urn:example:a£/bÆc//:://?=a=b#").rightValue
      iri.show shouldEqual "urn:example:a£/bÆc//:://?=a=b#"
    }

    "return an optional self" in {
      withHash.asUrn shouldEqual Some(withHash)
    }

    "return an optional self from asAbsolute" in {
      withHash.asUri shouldEqual Some(withHash)
    }

    "not be an Url" in {
      withHash.isUrl shouldEqual false
    }

    "not return a url" in {
      withHash.asUrl shouldEqual None
    }

    "not be a RelativeIri" in {
      withHash.isRelative shouldEqual false
    }

    "not return a RelativeIri" in {
      withHash.asRelative shouldEqual None
    }

    "eq" in {
      val lhs = Urn("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").rightValue
      val rhs = Urn("urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }
}
