package ch.epfl.bluebrain.nexus.rdf

import cats.kernel.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.Iri._

import scala.collection.immutable.{SortedMap, SortedSet}

class QuerySpec extends RdfSpec {

  "A Query" should {
    "be constructed successfully" in {
      // format: off
      val cases = List(
        "" -> SortedMap.empty[String, SortedSet[String]],
        "a=b&a=b&a=c&a&b&b&b=c&d/&e?" -> SortedMap(
          "a" -> SortedSet("", "b", "c"),
          "b" -> SortedSet("", "c"),
          "d/" -> SortedSet(""),
          "e?" -> SortedSet("")),
        "%3D%26=%3D%26&%3D&%26" -> SortedMap(
          "=&" -> SortedSet("=&"),
          "="  -> SortedSet(""),
          "&"  -> SortedSet("")),
        "%C2%A3=%C3%86" -> SortedMap("£" -> SortedSet("Æ"))
      )
      // format: on
      forAll(cases) {
        case (raw, map) =>
          Query(raw).rightValue.value shouldEqual map
      }
    }
    "fail to parse" in {
      val cases = List("a==b", "a=b&", "a#", "a&&", "a=&b")
      forAll(cases) { str => Query(str).leftValue }
    }
    "show" in {
      val encodedDelim = urlEncode("[]#")
      Query("a=b&a=b&a=c&a&b&b&b=c&d&e" + encodedDelim).rightValue.show shouldEqual "a&a=b&a=c&b&b=c&d&e" + encodedDelim
    }
    "pct encoded representation" in {
      val utf8         = "£Æ"
      val encodedUtf8  = urlEncode(utf8)
      val allowedDelim = "!:@!$()*,"
      val encodedDelim = urlEncode("[]#")
      Query(utf8 + encodedUtf8 + allowedDelim + encodedDelim).rightValue.pctEncoded shouldEqual (encodedUtf8 + encodedUtf8 + allowedDelim + encodedDelim)
    }
    "eq" in {
      val lhs = Query("a=b&a=b&a=c&a&b&b&b=c&d&e").rightValue
      val rhs = Query("a=b&a=b&a=c&a&b&b=c&d&e").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }
}
