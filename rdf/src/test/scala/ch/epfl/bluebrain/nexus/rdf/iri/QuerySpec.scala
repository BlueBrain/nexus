package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import io.circe.Json
import io.circe.syntax._

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
          Query(raw).rightValue.sorted shouldEqual map
      }
    }
    "fail to parse" in {
      val cases = List("a==b", "a=b&", "a#", "a&&", "a=&b")
      forAll(cases) { str => Query(str).leftValue }
    }
    "show" in {
      val encodedDelim = urlEncode("[]#")
      Query("a=b&a=b&a=c&a&b&b&b=c&d&e" + encodedDelim).rightValue.show shouldEqual "a=b&a=b&a=c&a&b&b&b=c&d&e" + encodedDelim
    }
    "fetch key" in {
      Query("a=b&a=c&b=d").rightValue.get("a") shouldEqual Set("b", "c")
      Query("a=b&a=c&b=d").rightValue.get("b") shouldEqual Set("d")
      Query("a=b&a=c&b=d").rightValue.get("r") shouldEqual Set.empty[String]
    }
    "pct encoded representation" in {
      val utf8         = "£Æ"
      val encodedUtf8  = urlEncode(utf8)
      val allowedDelim = "!:@!$()*,"
      val encodedDelim = urlEncode("[]#")
      Query(utf8 + encodedUtf8 + allowedDelim + encodedDelim).rightValue.uriString shouldEqual
        (encodedUtf8 + encodedUtf8 + allowedDelim + encodedDelim)
    }
    "eq" in {
      val lhs = Query("a=b&a=b&a=c&a&b&b&b=c&d&e").rightValue
      val rhs = Query("a=b&a=b&a=c&a&b&b=c&d&e").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
    "encode" in {
      val queryStr = "a=b&a=c&b=d"
      Query(queryStr).rightValue.asJson shouldEqual Json.fromString(queryStr)
    }
    "decode" in {
      val queryStr = "a=b&a=c&b=d"
      Json.fromString(queryStr).as[Query].rightValue shouldEqual Query(queryStr).rightValue
    }
  }
}
