package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.Iri._

class PathSpec extends RdfSpec {

  "A Path" should {
    val abcd = Path("/a/b//c/d")
    "be parsed in the correct ADT" in {
      // format: off
      val cases = List(
        ""                        -> Empty,
        "/"                       -> Slash(Empty),
        "///"                     -> Slash(Slash(Slash(Empty))),
        "/a/b//c/d"               -> Segment("d", Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//c//"               -> Slash(Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//:@//"              -> Slash(Slash(Segment(":@", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b//:://"              -> Slash(Slash(Segment("::", Slash(Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))))),
        "/a/b/%20/:://"           -> Slash(Slash(Segment("::", Slash(Segment(" ", Slash(Segment("b", Slash(Segment("a", Slash(Empty)))))))))),
        "/a£/bÆc//:://"           -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Slash(Empty))))))))),
        "/a%C2%A3/b%C3%86c//:://" -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Slash(Empty)))))))))
      )
      // format: on
      forAll(cases) {
        case (str, expected) =>
          Path(str).rightValue shouldEqual expected
      }
    }

    "be parsed in the correct ADT for rootless paths" in {
      // format: off
      val cases = List(
        "a/b//c/d"               -> Segment("d", Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))),
        "a/b//c//"               -> Slash(Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))),
        "a/b//:@//"              -> Slash(Slash(Segment(":@", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))),
        "a/b//:://"              -> Slash(Slash(Segment("::", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))),
        "a/b/%20/:://"           -> Slash(Slash(Segment("::", Slash(Segment(" ", Slash(Segment("b", Slash(Segment("a", Empty))))))))),
        "a£/bÆc//:://"           -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Empty)))))))),
        "a%C2%A3/b%C3%86c//:://" -> Slash(Slash(Segment("::", Slash(Slash(Segment("bÆc", Slash(Segment("a£", Empty))))))))
      )
      // format: on
      forAll(cases) {
        case (str, expected) =>
          Path.rootless(str).rightValue shouldEqual expected
      }
    }
    "fail to construct for invalid chars" in {
      val cases = List("/a/b?", "abc", "/a#", ":asd", " ")
      forAll(cases) { c => Path(c).leftValue }
    }
    "normalize paths" in {
      val cases = List(
        ("/a/b/../c/", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/"),
        ("/../../../", Slash(Empty), "/"),
        ("/a/./b/./c/./", Slash(Segment("c", Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))), "/a/b/c/"),
        ("/a//../b/./c/./", Slash(Segment("c", Slash(Segment("b", Slash(Segment("a", Slash(Empty))))))), "/a/b/c/"),
        ("/a/./b/../c/./", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/"),
        ("/a/c/../", Slash(Segment("a", Slash(Empty))), "/a/"),
        ("/a/c/./", Slash(Segment("c", Slash(Segment("a", Slash(Empty))))), "/a/c/")
      )
      forAll(cases) {
        case (str, expected, expectedStr) =>
          val value = Path(str).rightValue
          value shouldEqual expected
          value.show shouldEqual expectedStr
      }
    }
    "return the correct information about the internal structure" in {
      val cases = List(
        ("", true, false, false, Some(Empty), None, None),
        ("/", false, true, false, None, Some(Slash(Empty)), None),
        ("/a/", false, true, false, None, Some(Slash(Segment("a", Slash(Empty)))), None),
        ("/a", false, false, true, None, None, Some(Segment("a", Slash(Empty))))
      )
      forAll(cases) {
        case (str, isEmpty, isSlash, isSegment, asEmpty, asSlash, asSegment) =>
          val p = Path(str).rightValue
          p.isEmpty shouldEqual isEmpty
          p.isSlash shouldEqual isSlash
          p.isSegment shouldEqual isSegment
          p.asEmpty shouldEqual asEmpty
          p.asSlash shouldEqual asSlash
          p.asSegment shouldEqual asSegment
      }
    }
    "show" in {
      val encodedDelims = urlEncode("/#[]?")
      Path("/" + encodedDelims + "/%20a/b//c/d/£¤¥").rightValue.show shouldEqual "/" + encodedDelims + "/%20a/b//c/d/£¤¥"
    }
    "pct encoded representation" in {
      val encodedDelims = urlEncode("/#[]?")
      val utf8          = "£¤¥"
      val utf8Encoded   = urlEncode(utf8)
      Path("/" + encodedDelims + "/a/b//c/d/" + utf8 + "/" + utf8Encoded).rightValue.pctEncoded shouldEqual
        "/" + encodedDelims + "/a/b//c/d/" + utf8Encoded + "/" + utf8Encoded
    }
    "be slash" in {
      Path("/").rightValue.isSlash shouldEqual true
    }
    "not be slash" in {
      abcd.rightValue.isSlash shouldEqual false
    }
    "end with slash" in {
      Path("/a/b//c/d/").rightValue.endsWithSlash shouldEqual true
    }
    "not end with slash" in {
      abcd.rightValue.endsWithSlash shouldEqual false
    }
    "show decoded" in {
      Path("/a%C2%A3/b%C3%86c//:://").rightValue.show shouldEqual "/a£/bÆc//:://"
    }
    "eq" in {
      Eq.eqv(abcd.rightValue, Segment("d", Path("/a/b//c/").rightValue)) shouldEqual true
    }

    "start with slash" in {
      val cases = List("/", "///", "/a/b/c/d", "/a/b/c/d/")
      forAll(cases)(str => Path(str).rightValue.startWithSlash shouldEqual true)
    }

    "does not start with slash" in {
      val cases = List(Empty, Segment("d", Slash(Segment("c", Slash(Slash(Segment("b", Slash(Segment("a", Empty)))))))))
      forAll(cases)(p => p.startWithSlash shouldEqual false)
    }

    "reverse" in {
      val cases = List(
        Path("/a/b").rightValue    -> Slash(Segment("a", Slash(Segment("b", Empty)))),
        Empty                      -> Empty,
        Path("/a/b/c/").rightValue -> Path("/c/b/a/").rightValue,
        Path./                     -> Path./
      )
      forAll(cases) {
        case (path, reversed) =>
          path.reverse shouldEqual reversed
          path.reverse.reverse shouldEqual path
      }
    }

    "concatenate segments" in {
      segment("a").rightValue / "b" / "c" shouldEqual Segment("c", Slash(Segment("b", Slash(Segment("a", Empty)))))
    }

    "build" in {
      val path = "a" / "b" / "c"
      path shouldEqual Path("/a/b/c").rightValue
    }

    "join two paths" in {
      val cases = List(
        (Path("/e/f").rightValue, Path("/a/b/c/d").rightValue)           -> Path("/a/b/c/d/e/f").rightValue,
        (segment("ghi").rightValue / "f", Path("/a/b/c/def").rightValue) -> Path("/a/b/c/defghi/f").rightValue,
        (Empty, Path("/a/b").rightValue)                                 -> Path("/a/b").rightValue,
        (Empty, Slash(Empty))                                            -> Slash(Empty),
        (Slash(Empty), Empty)                                            -> Slash(Empty),
        (Path("/e/f/").rightValue, Path("/a/b/c/d").rightValue)          -> Path("/a/b/c/d/e/f/").rightValue,
        (Path("/e/f/").rightValue, Path("/a/b/c/d/").rightValue)         -> Path("/a/b/c/d/e/f/").rightValue,
        (Empty, Empty)                                                   -> Empty
      )
      forAll(cases) {
        case ((left, right), expected) => left :: right shouldEqual expected
      }
      Path("/e/f/").rightValue.prepend(Path("/a/b/c/d/").rightValue, allowSlashDup = true) shouldEqual Path(
        "/a/b/c/d//e/f/"
      ).rightValue
    }

    "return the head" in {
      Path("/a/b/c/").rightValue.head shouldEqual '/'
      Path("/a/b/c").rightValue.head shouldEqual "c"
      Path.Empty.head shouldEqual Path.Empty
    }

    "return the tail" in {
      Path("/a/b/c/").rightValue.tail() shouldEqual Path("/a/b/c").rightValue
      Path("/a/b/c").rightValue.tail() shouldEqual Path("/a/b/").rightValue
      Path.Empty.tail() shouldEqual Path.Empty
      Path("/a/b/c").rightValue.tail().tail().tail().tail().tail() shouldEqual Path("/").rightValue

      Path("/a/b/c/").rightValue.tail(dropSlash = true) shouldEqual Path("/a/b/c").rightValue
      Path("/a/b/c///").rightValue.tail(dropSlash = true) shouldEqual Path("/a/b/c").rightValue
      Path("/a/b/c").rightValue.tail().tail(dropSlash = true) shouldEqual Path("/a/b").rightValue
      Path.Empty.tail(dropSlash = true) shouldEqual Path.Empty
      Path("/a/b/c").rightValue.tail(dropSlash = true).tail(dropSlash = true) shouldEqual Path("/a").rightValue
    }

    "to segments" in {
      val cases =
        List(Path("/a/b/c/d/e/").rightValue, Path("/a//b/c//d//e//").rightValue, Path("/a/b/c/d/e").rightValue)
      forAll(cases) { path => path.segments shouldEqual List("a", "b", "c", "d", "e") }
      Path.Empty.segments shouldEqual List.empty[String]
      Path("/a/").rightValue.segments shouldEqual List("a")
    }

    "return the last segment" in {
      Path("/a/b/c").rightValue.lastSegment shouldEqual Some("c")
      Path("/a/b/c/").rightValue.lastSegment shouldEqual Some("c")
      Path("/a/b//c//").rightValue.lastSegment shouldEqual Some("c")
      Path.Empty.lastSegment shouldEqual None
    }

    "number of segments" in {
      val cases =
        List(Path("/a/b/c/d/e/").rightValue, Path("/a//b/c//d//e//").rightValue, Path("/a/b/c/d/e").rightValue)
      forAll(cases) { path => path.size shouldEqual 5 }
      Path.Empty.size shouldEqual 0
      Path("/a/").rightValue.size shouldEqual 1
    }

    "starts with another path" in {
      val cases = List(
        Path("/a/b//c/d") -> true,
        Path("/a/b//c")   -> true,
        Path("/a/b//")    -> true,
        Path("/a")        -> true,
        Path("/")         -> true,
        Path("//")        -> false,
        Path("/a/c")      -> false
      )
      forAll(cases) {
        case (other, expected) => abcd.rightValue startsWith other.rightValue shouldEqual expected
      }
      Path("/").rightValue startsWith Path("/").rightValue shouldEqual true
      Path.Empty startsWith Path("/").rightValue shouldEqual false
    }
  }
}
