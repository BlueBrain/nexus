package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Host
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Host.{IPv4Host, NamedHost}
import io.circe.Json
import io.circe.syntax._

class HostSpec extends RdfSpec {

  "An IPv4Host" should {
    val one = Host.ipv4("1.1.1.1").rightValue
    "be parsed correctly from string" in {
      val values = List("127.0.0.1", "255.255.255.255", "199.99.9.0", "249.249.249.249")
      forAll(values) { v => Host.ipv4(v).rightValue.show shouldEqual v }
    }
    "be constructed correctly from bytes" in {
      Host.ipv4(1, 1, 1, 1).show shouldEqual one.show
    }
    "be constructed correctly from array" in {
      Host.ipv4(Array.fill[Byte](4)(1)).rightValue.show shouldEqual one.show
    }
    "fail to construct from incorrect array length" in {
      forAll(List(Array.fill[Byte](3)(1), Array.fill[Byte](5)(1))) { arr =>
        Host.ipv4(arr).leftValue should not be empty
      }
    }
    "fail to parse from string" in {
      val values = List(
        "1",
        "1.1.1",
        "1.1.1.",
        "1..1.1",
        "a.b.c.d",
        "01.1.1.1",
        "1.01.1.1",
        "1.1.01.1",
        "1.1.1.01",
        "1.1.1.1a",
        "256.255.255.255",
        "255.256.255.255",
        "255.255.256.255",
        "255.255.255.256",
        "355.255.255.255",
        "260.255.255.256"
      )
      forAll(values) { v => Host.ipv4(v).leftValue should not be empty }
    }
    "be an IPv4 address" in {
      one.isIPv4 shouldEqual true
    }
    "return itself as IPv4 address" in {
      one.asIPv4 shouldEqual Some(one)
    }
    "not be an IPv6 address" in {
      one.isIPv6 shouldEqual false
    }
    "not be a named host" in {
      one.isNamed shouldEqual false
    }
    "not return an IPv6Host" in {
      one.asIPv6 shouldEqual None
    }
    "not return a NamedHost" in {
      one.asNamed shouldEqual None
    }
    "show" in {
      one.show shouldEqual "1.1.1.1"
    }
    "eq" in {
      Eq.eqv(Host.ipv4("1.1.1.1").rightValue, one) shouldEqual true
    }
    "encode" in {
      forAll(List("127.0.0.1", "255.255.255.255", "199.99.9.0", "249.249.249.249")) { str =>
        Host.ipv4(str).rightValue.asJson shouldEqual Json.fromString(str)
      }
    }
    "decode" in {
      forAll(List("127.0.0.1", "255.255.255.255", "199.99.9.0", "249.249.249.249")) { str =>
        Json.fromString(str).as[IPv4Host].rightValue shouldEqual Host.ipv4(str).rightValue

      }
    }
  }

  "An IPv6Host" should {
    val one       = Host.ipv6(Array.fill(16)(1.toByte))
    val oneString = "101:101:101:101:101:101:101:101"
    "be constructed correctly from array" in {
      one.rightValue.show shouldEqual oneString
    }
    "be rendered correctly as mixed ipv4/ipv6" in {
      one.rightValue.asMixedString shouldEqual "101:101:101:101:101:101:1.1.1.1"
    }
    "fail to construct from incorrect array length" in {
      forAll(List(Array.fill(17)(1.toByte), Array.fill(3)(1.toByte), Array.fill(15)(1.toByte))) { arr =>
        Host.ipv6(arr).leftValue should not be empty
      }
    }
    "be an IPv6 address" in {
      one.rightValue.isIPv6 shouldEqual true
    }
    "return itself as IPv6 address" in {
      one.rightValue.asIPv6 shouldEqual Some(one.rightValue)
    }
    "not be an IPv4 address" in {
      one.rightValue.isIPv4 shouldEqual false
    }
    "not be a named host" in {
      one.rightValue.isNamed shouldEqual false
    }
    "not return an IPv4Host" in {
      one.rightValue.asIPv4 shouldEqual None
    }
    "not return a NamedHost" in {
      one.rightValue.asNamed shouldEqual None
    }
    "show" in {
      one.rightValue.show shouldEqual oneString
    }
    "eq" in {
      Eq.eqv(Host.ipv6(Array.fill(16)(1.toByte)).rightValue, one.rightValue) shouldEqual true
    }
  }

  "A NamedHost" should {
    val pct =
      "%C2%A3%C2%A4%C2%A5%C2%A6%C2%A7%C2%A8%C2%A9%C2%AA%C2%AB%C2%AC%C2%AD%C2%AE%C2%AF%C2%B0%C2%B1%C2%B2%C2%B3%C2%B4%C2%B5%C2%B6%C2%B7%C2%B8%C2%B9%C2%BA%C2%BB%C2%BC%C2%BD%C2%BE%C2%BF%C3%80%C3%81%C3%82%C3%83%C3%84%C3%85%C3%86"
    val pctLow =
      "%C2%A3%C2%A4%C2%A5%C2%A6%C2%A7%C2%A8%C2%A9%C2%AA%C2%AB%C2%AC%C2%AD%C2%AE%C2%AF%C2%B0%C2%B1%C2%B2%C2%B3%C2%B4%C2%B5%C2%B6%C2%B7%C2%B8%C2%B9%C2%BA%C2%BB%C2%BC%C2%BD%C2%BE%C2%BF%C3%A0%C3%A1%C3%A2%C3%A3%C3%A4%C3%A5%C3%A6"
    val ucsUp  = "£¤¥¦§¨©ª«¬\u00AD®¯°±²³´µ¶·¸¹º»¼½¾¿ÀÁÂÃÄÅÆ"
    val ucsLow = "£¤¥¦§¨©ª«¬\u00AD®¯°±²³´µ¶·¸¹º»¼½¾¿àáâãäåæ"
    val delims = "!$&'()*+,;="
    val up     = "ABCD"
    val low    = "abcd"

    "be parsed correctly from a string" in {
      Host.named("epfl.ch").rightValue.value shouldEqual "epfl.ch"
    }

    "be parsed correctly from percent encoded string" in {
      Host.named(pct).rightValue.value shouldEqual ucsLow
    }

    "be parsed correctly from ucs chars" in {
      Host.named(ucsUp).rightValue.value shouldEqual ucsLow
    }

    "be parsed correctly from delimiters" in {
      Host.named(delims).rightValue.value shouldEqual delims
    }

    "be parsed correctly from mixed characters" in {
      Host.named(ucsUp + pct + delims + up).rightValue.value shouldEqual (ucsLow + ucsLow + delims + low)
    }

    "show" in {
      val encodedDelim = urlEncode("[]#")
      Host.named(up + encodedDelim).rightValue.show shouldEqual (low + encodedDelim)
      Host.named(up + encodedDelim).rightValue.asInstanceOf[Host].show shouldEqual (low + encodedDelim)
    }

    "pct encoded representation" in {
      val encodedDelim = urlEncode("[]#")
      Host.named(ucsUp + pct + ucsLow + delims + up + encodedDelim).rightValue.uriString shouldEqual
        (pctLow + pctLow + pctLow + delims + low + encodedDelim)
    }

    "be named" in {
      Host.named(up).rightValue.isNamed shouldEqual true
    }

    "return an optional self" in {
      Host.named(up).rightValue.asNamed shouldEqual Some(Host.named(up).rightValue)
    }

    "eq" in {
      Eq.eqv(Host.named(ucsUp).rightValue, Host.named(ucsLow).rightValue) shouldEqual true
    }
    "encode" in {
      val name = "epfl.ch"
      Host.named("epfl.ch").rightValue.asJson shouldEqual Json.fromString(name)
    }
    "decode" in {
      val name = "epfl.ch"
      Json.fromString(name).as[NamedHost].rightValue shouldEqual Host.named(name).rightValue
    }
  }
}
