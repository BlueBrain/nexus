package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.{RelativeIri, Uri, Url}
import io.circe.Json
import io.circe.syntax._

class RelativeIriSpec extends RdfSpec {

  "A RelativeIri" should {
    val correctCases = List(
      "//me:me@hOst:443/a/b?a&e=f&b=c#frag" -> "//me:me@host:443/a/b?a&e=f&b=c#frag",
      "//me:me@hOst#frag"                   -> "//me:me@host#frag",
      "/some/:/path"                        -> "/some/:/path",
      "a/../b/./c"                          -> "b/c",
      "../../../"                           -> "../../../",
      "/../../"                             -> "/",
      "/:/some/path"                        -> "/:/some/path",
      "some/:/path"                         -> "some/:/path",
      "?q=v"                                -> "?q=v",
      "#frag"                               -> "#frag",
      "//hOst:443/a/b/../c"                 -> "//host:443/a/c",
      "//1.2.3.4:80/a%C2%A3/b%C3%86c//:://" -> "//1.2.3.4:80/a£/bÆc//:://",
      "//1.2.3.4:80/a%C2%A3/b%C3%86c//:://" -> "//1.2.3.4:80/a£/bÆc//:://",
      "//1.2.3.4:80/a%C2%A3/b%C3%86c//:://" -> "//1.2.3.4:80/a£/bÆc//:://"
    )
    "be parsed correctly" in {
      forAll(correctCases) {
        case (in, expected) => RelativeIri(in).rightValue.iriString shouldEqual expected
      }
    }

    "aaa" in {
      val uri      = Uri("http://example.com/some/deep/directory/and/file#with-a-fragment").rightValue
      val relative = RelativeIri("relativeIris").rightValue
      println(relative.resolve(uri))
    }

    "fail to parse from string" in {
      val cases = List(
        "http://me:me@hOst:443/a/b?a&e=f&b=c#frag",
        ":/some/path",
        " ",
        ""
      )
      forAll(cases)(in => RelativeIri(in).leftValue should not be empty)
    }
    val withHash = Iri.relative("//1.2.3.4:80/a%C2%A3/b%C3%86c//:://#hash").rightValue

    "be relative" in {
      withHash.isRelative shouldEqual true
    }

    "return an optional self" in {
      withHash.asRelative shouldEqual Some(withHash)
    }

    "not be an Urn" in {
      withHash.isUrn shouldEqual false
    }

    "not be a Uri" in {
      withHash.isUri shouldEqual false
    }

    "not return a Uri" in {
      withHash.asUri shouldEqual None
    }

    "not be an Url" in {
      withHash.isUrl shouldEqual false
    }

    "not return a urn" in {
      withHash.asUrn shouldEqual None
    }

    "not return a url" in {
      withHash.asUrl shouldEqual None
    }

    "eq" in {
      val lhs = RelativeIri("a/./b/../?q=asd#1").rightValue
      val rhs = RelativeIri("a/?q=asd#1").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }

    "resolve from base url http://a/b/c/d;p?q" in {
      val base = Url("http://a/b/c/d;p?q").rightValue
      val cases = List(
        "g"             -> "http://a/b/c/g",
        "./g"           -> "http://a/b/c/g",
        "g/"            -> "http://a/b/c/g/",
        "/g"            -> "http://a/g",
        "//g"           -> "http://g",
        "?y"            -> "http://a/b/c/d;p?y",
        "g?y"           -> "http://a/b/c/g?y",
        "#s"            -> "http://a/b/c/d;p?q#s",
        "g#s"           -> "http://a/b/c/g#s",
        "g?y#s"         -> "http://a/b/c/g?y#s",
        ";x"            -> "http://a/b/c/;x",
        "g;x"           -> "http://a/b/c/g;x",
        "g;x?y#s"       -> "http://a/b/c/g;x?y#s",
        "."             -> "http://a/b/c/",
        "./"            -> "http://a/b/c/",
        ".."            -> "http://a/b/",
        "../"           -> "http://a/b/",
        "../g"          -> "http://a/b/g",
        "../.."         -> "http://a/",
        "../../"        -> "http://a/",
        "../../../../"  -> "http://a/",
        ".././g"        -> "http://a/b/g",
        "../../g"       -> "http://a/g",
        "../../../g"    -> "http://a/g",
        "../../../../g" -> "http://a/g",
        "..g"           -> "http://a/b/c/..g",
        "g."            -> "http://a/b/c/g.",
        ".g"            -> "http://a/b/c/.g",
        "g.."           -> "http://a/b/c/g..",
        "/../g"         -> "http://a/g",
        "./../g"        -> "http://a/b/g",
        "g/./h"         -> "http://a/b/c/g/h",
        "g/../h"        -> "http://a/b/c/h",
        "g;x=1/./y"     -> "http://a/b/c/g;x=1/y",
        "g;x=1/../y"    -> "http://a/b/c/y"
      )
      forAll(cases) {
        case (in, result) =>
          RelativeIri(in).rightValue.resolve(base) shouldEqual Url(result).rightValue
      }
    }

    "resolve from base url http://a/b/c/" in {
      val base = Url("http://a/b/c/").rightValue
      val cases = List(
        "g"            -> "http://a/b/c/g",
        "./g"          -> "http://a/b/c/g",
        "g/"           -> "http://a/b/c/g/",
        "/g"           -> "http://a/g",
        "//g"          -> "http://g",
        "?y"           -> "http://a/b/c/?y",
        "g?y"          -> "http://a/b/c/g?y",
        "#s"           -> "http://a/b/c/#s",
        "g#s"          -> "http://a/b/c/g#s",
        "g?y#s"        -> "http://a/b/c/g?y#s",
        ";x"           -> "http://a/b/c/;x",
        "g;x"          -> "http://a/b/c/g;x",
        "g;x?y#s"      -> "http://a/b/c/g;x?y#s",
        "."            -> "http://a/b/c/",
        "./"           -> "http://a/b/c/",
        ".."           -> "http://a/b/",
        "../"          -> "http://a/b/",
        "../g"         -> "http://a/b/g",
        "../.."        -> "http://a/",
        "../../"       -> "http://a/",
        "../../../../" -> "http://a/",
        "../../g"      -> "http://a/g"
      )
      forAll(cases) {
        case (in, result) => RelativeIri(in).rightValue.resolve(base) shouldEqual Url(result).rightValue
      }
    }

    "resolve from base url http://a/b/c/d#fragment" in {
      val base = Url("http://a/b/c/").rightValue
      val cases = List(
        "g"            -> "http://a/b/c/g",
        "./g"          -> "http://a/b/c/g",
        "g/"           -> "http://a/b/c/g/",
        "/g"           -> "http://a/g",
        "//g"          -> "http://g",
        "?y"           -> "http://a/b/c/?y",
        "g?y"          -> "http://a/b/c/g?y",
        "#s"           -> "http://a/b/c/#s",
        "g#s"          -> "http://a/b/c/g#s",
        "g?y#s"        -> "http://a/b/c/g?y#s",
        ";x"           -> "http://a/b/c/;x",
        "g;x"          -> "http://a/b/c/g;x",
        "g;x?y#s"      -> "http://a/b/c/g;x?y#s",
        "."            -> "http://a/b/c/",
        "./"           -> "http://a/b/c/",
        ".."           -> "http://a/b/",
        "../"          -> "http://a/b/",
        "../g"         -> "http://a/b/g",
        "../.."        -> "http://a/",
        "../../"       -> "http://a/",
        "../../../../" -> "http://a/",
        "../../g"      -> "http://a/g"
      )
      forAll(cases) {
        case (in, result) => RelativeIri(in).rightValue.resolve(base) shouldEqual Url(result).rightValue
      }
    }
    "encode" in {
      forAll(correctCases) {
        case (cons, str) => RelativeIri(cons).rightValue.asJson shouldEqual Json.fromString(str)
      }
    }
    "decode" in {
      forAll(correctCases) {
        case (cons, str) => Json.fromString(str).as[RelativeIri].rightValue shouldEqual RelativeIri(cons).rightValue
      }
    }
  }
}
