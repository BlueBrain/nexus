package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Urn

class IriSpec extends RdfSpec {
  "An Iri" should {
    val casesRelative = List(
      "//me:me@hOst:443/a/b?a&e=f&b=c#frag" -> "//me:me@host:443/a/b?a&e=f&b=c#frag",
      "//me:me@hOst#frag"                   -> "//me:me@host#frag",
      "/some/:/path"                        -> "/some/:/path"
    )
    val casesRelativeEncoded = List(
      "/some/!/path/€/other" -> "/some/!/path/%E2%82%AC/other"
    )
    val casesUrn = List(
      "urn:uUid:6e8bc430-9c3a-11d9-9669-0800200c9a66"      -> "urn:uuid:6e8bc430-9c3a-11d9-9669-0800200c9a66",
      "urn:lex:eu:council:directive:2010-03-09;2010-19-UE" -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"
    )
    val casesUrnEncoded = List(
      "urn:example:a%C2%A3/b%C3%86c//:://?=a=£"            -> "urn:example:a%C2%A3/b%C3%86c//:://?=a=%C2%A3",
      "urn:lex:eu:council:directive:2010-03-09;2010-19-UE" -> "urn:lex:eu:council:directive:2010-03-09;2010-19-UE"
    )
    val casesUrl = List(
      "hTtps://me:me@hOst:443/a%20path/b?a&e=f&b=c#frag" -> "https://me:me@host/a%20path/b?a&e=f&b=c#frag",
      "hTtps://me:me@hOst#frag"                          -> "https://me:me@host#frag"
    )

    val casesUrlEncoded = List(
      "hTtp://hOst%C2%A3:80/a%C2%A3/b%C3%86c//%3A%3A//"    -> "http://host%C2%A3/a%C2%A3/b%C3%86c//:://",
      "hTtp://hOst%C2%A3:80/a%C2%A3/b%C3%86c//%3A%3A/%20/" -> "http://host%C2%A3/a%C2%A3/b%C3%86c//::/%20/"
    )

    "be parsed correctly into a relative uri" in {
      forAll(casesRelative) {
        case (in, expected) =>
          val iri = Iri(in).rightValue
          iri shouldEqual Iri.relative(in).rightValue
          iri.iriString shouldEqual expected
          iri.show shouldEqual expected
      }
    }

    "be parsed correctly into a urn" in {
      forAll(casesUrn) {
        case (in, expected) =>
          val iri = Iri(in).rightValue
          iri shouldEqual Iri.urn(in).rightValue
          iri.iriString shouldEqual expected
      }
    }

    "be parsed correctly into a url" in {
      forAll(casesUrl) {
        case (in, expected) =>
          val iri = Iri(in).rightValue
          iri shouldEqual Iri.url(in).rightValue
          iri.iriString shouldEqual expected
      }
    }

    "avoid double pct-encoding" in {
      val iri = Iri.uri("http://example.com/a/path").rightValue / "http%3A%2F%2Fsome.com%2Fa%2Fb%C3%86c"
      iri.iriString shouldEqual "http://example.com/a/path/http%3A%2F%2Fsome.com%2Fa%2Fb%C3%86c"
      iri.iriString shouldEqual iri.uriString
    }

    "show as url" in {
      forAll(casesUrlEncoded) {
        case (in, expected) =>
          Iri(in).rightValue.uriString shouldEqual expected
      }
    }

    "show as uri" in {
      forAll(casesUrnEncoded) {
        case (in, expected) =>
          Iri(in).rightValue.uriString shouldEqual expected
      }
    }

    "show as relative uri" in {
      forAll(casesRelativeEncoded) {
        case (in, expected) =>
          Iri(in).rightValue.uriString shouldEqual expected
      }
    }

    "eq relative" in {
      val lhs = Iri("/?q=asd#1").rightValue
      val rhs = Iri("/?q=asd#1").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "eq urn" in {
      val lhs = Urn("urn:examp-lE:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").rightValue
      val rhs = Urn("urn:examp-le:foo-bar-baz-qux?+CCResolve:cc=uk?=a=b#hash").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "eq url" in {
      val lhs = Iri("hTtp://gooGle.com/?q=asd#1").rightValue
      val rhs = Iri("http://google.com/?q=asd#1").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }

}
