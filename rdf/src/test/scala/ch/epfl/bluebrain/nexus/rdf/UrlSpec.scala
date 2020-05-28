package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri._

class UrlSpec extends RdfSpec {

  "An Url" should {
    "be parsed correctly" in {
      val cases = List(
        "hTtps://me:me@hOst:443/a/b?a&e=f&b=c#frag"                                                  -> "https://me:me@host/a/b?a&b=c&e=f#frag",
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
        "http://google.com/a/../search/."                                                            -> "http://google.com/search/",
        "http://google.com/a/../search/.."                                                           -> "http://google.com",
        "https://my%40user:my%3Apassword%24@myhost.com/a/path/http%3A%2F%2Fexample.com%2Fnxv%3Asome" -> "https://my%40user:my:password$@myhost.com/a/path/http:%2F%2Fexample.com%2Fnxv:some",
        "http://abc%40:def@example.com/a/http%3A%2F%2Fother.com"                                     -> "http://abc%40:def@example.com/a/http:%2F%2Fother.com"
      )
      forAll(cases) {
        case (in, expected) => Url(in).rightValue.asString shouldEqual expected
      }
    }
    val withHash = Iri.absolute("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c//:://#hash").rightValue

    "be absolute" in {
      withHash.isAbsolute shouldEqual true
    }

    "be a Url" in {
      withHash.isUrl shouldEqual true
    }

    "as uri" in {
      val iri = Iri.absolute("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c/£/#hash").rightValue
      iri.asUri shouldEqual "http://1.2.3.4/a%C2%A3/b%C3%86c/%C2%A3/#hash"
    }

    "show" in {
      val iri = Iri.absolute("hTtp://1.2.3.4:80/a%C2%A3/b%C3%86c/£/#hash").rightValue
      iri.show shouldEqual "http://1.2.3.4/a£/bÆc/£/#hash"
    }

    "return an optional self" in {
      withHash.asUrl shouldEqual Some(withHash)
    }

    "return an optional self from asAbsolute" in {
      withHash.asAbsolute shouldEqual Some(withHash)
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
        case (in, segment, expected) => (Url(in).rightValue + segment) shouldEqual Url(expected).rightValue
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
        case (in, p, expected) => (Url(in).rightValue + Path(p).rightValue) shouldEqual Url(expected).rightValue
      }
      Url("http://google.com/a").rightValue + ("b" / "c") shouldEqual Url("http://google.com/a/b/c").rightValue
    }

    "eq" in {
      val lhs = Url("hTtp://gooGle.com/a/../search?q=asd#1").rightValue
      val rhs = Url("http://google.com/search?q=asd#1").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }
}
