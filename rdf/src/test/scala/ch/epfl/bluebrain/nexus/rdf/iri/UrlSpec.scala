package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Iri._
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.Json
import io.circe.syntax._

class UrlSpec extends RdfSpec {

  "An Url" should {
    val correctCases = List(
      "hTtps://me:me@hOst:443/a/b?a&e=f&b=c#frag"                                                  -> "https://me:me@host/a/b?a&e=f&b=c#frag",
      "hTtps://me:me@hOst#frag"                                                                    -> "https://me:me@host#frag",
      "hTtps://me:me@hOst?"                                                                        -> "https://me:me@host?",
      "hTtp://hOst%C2%A3:80/a%C2%A3/b%C3%86c//:://"                                                -> "http://host£/a£/bÆc//:://",
      "hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c//:://"                                                   -> "http://1.2.3.4/a£/bÆc//:://",
      "hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c//:://"                                                   -> "http://1.2.3.4/a£/bÆc//:://",
      "http://google.com/#"                                                                        -> "http://google.com/#",
      "FiLe:///bin/bash"                                                                           -> "file:///bin/bash",
      "FiLe:/bin/bash"                                                                             -> "file:/bin/bash",
      "MailtO:test@example.com"                                                                    -> "mailto:test@example.com",
      "http://google.com/.."                                                                       -> "http://google.com",
      "http://google.com/./"                                                                       -> "http://google.com/",
      "http://google.com/a/../search/."                                                            -> "http://google.com/search",
      "http://google.com/a/../search/.."                                                           -> "http://google.com",
      "https://my%40user:my%3Apassword%24@myhost.com/a/path/http%3A%2F%2Fexample.com%2Fnxv%3Asome" -> "https://my%40user:my:password$@myhost.com/a/path/http:%2F%2Fexample.com%2Fnxv:some",
      "http://abc%40:def@example.com/a/http%3A%2F%2Fother.com"                                     -> "http://abc%40:def@example.com/a/http:%2F%2Fother.com"
    )
    "be parsed correctly" in {
      forAll(correctCases) {
        case (in, expected) => Url(in).rightValue.iriString shouldEqual expected
      }
    }
    val withHash = Iri.uri("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c//:://#hash").rightValue

    "be a Uri" in {
      withHash.isUri shouldEqual true
    }

    "be a Url" in {
      withHash.isUrl shouldEqual true
    }

    "as uri" in {
      val iri = Iri.uri("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c/£/#hash").rightValue
      iri.uriString shouldEqual "http://1.2.3.4/a%C2%A3/b%C3%86c/%C2%A3/#hash"
    }

    "show" in {
      val iri = Iri.uri("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c/£/#hash").rightValue
      iri.show shouldEqual "http://1.2.3.4/a£/bÆc/£/#hash"
    }

    "return an optional self" in {
      withHash.asUrl shouldEqual Some(withHash)
    }

    "return an optional self from asUri" in {
      withHash.asUri shouldEqual Some(withHash)
    }

    "not be an Urn" in {
      withHash.isUrn shouldEqual false
    }

    "not return a urn" in {
      withHash.asUrn shouldEqual None
    }

    "not be a RelativeIri" in {
      withHash.isRelative shouldEqual false
    }

    "not return a RelativeIri" in {
      withHash.asRelative shouldEqual None
    }

    "append segment" in {
      val cases = List(
        ("http://google.com/a/", "bcd", "http://google.com/a/bcd"),
        ("http://google.com/a/", "/bcd", "http://google.com/a/bcd"),
        ("http://google.com/a/", "/", "http://google.com/a/"),
        ("http://google.com/a?one=two&three", "bcd", "http://google.com/a/bcd?one=two&three"),
        ("http://google.com/a#other", "bcd", "http://google.com/a/bcd#other")
      )
      forAll(cases) {
        case (in, segment, expected) => (Url(in).rightValue / segment) shouldEqual Url(expected).rightValue
      }
    }

    "append path" in {
      val cases = List(
        ("http://google.com/a/", "/b/c/d", "http://google.com/a/b/c/d"),
        ("http://google.com/a/", "/", "http://google.com/a/"),
        ("http://google.com/a/", "/bcd", "http://google.com/a/bcd"),
        ("http://google.com/a?one=two&three", "/b/c/d", "http://google.com/a/b/c/d?one=two&three"),
        ("http://google.com/a#other", "/b/c/d", "http://google.com/a/b/c/d#other")
      )
      forAll(cases) {
        case (in, p, expected) => (Url(in).rightValue / Path(p).rightValue) shouldEqual Url(expected).rightValue
      }
      Url("http://google.com/a").rightValue / ("b" / "c") shouldEqual Url("http://google.com/a/b/c").rightValue
    }

    "eq" in {
      val lhs = Url("hTtp://gooGle.com/a/../search?q=asd#1").rightValue
      val rhs = Url("http://google.com/search?q=asd#1").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "encode" in {
      forAll(correctCases) {
        case (cons, str) => Iri.url(cons).rightValue.asJson shouldEqual Json.fromString(str)
      }
    }

    "decode" in {
      forAll(correctCases) {
        case (cons, str) => Json.fromString(str).as[Url].rightValue shouldEqual Iri.url(cons).rightValue
      }
    }
  }
}
