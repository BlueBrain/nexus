package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec

class SchemeSpec extends RdfSpec {

  "A Scheme" should {
    "be constructed successfully" in {
      val strings = List("urn", "https", "http", "file", "ftp", "ssh", "a", "a0", "a-", "a+", "a.", "HTTPS", "A0-+.")
      forAll(strings) { s => Scheme(s).rightValue }
    }
    "fail to construct" in {
      val strings = List("", "0", "0a", "as_", "%20a", "0-+.")
      forAll(strings) { s => Scheme(s).leftValue }
    }
    "return the appropriate boolean flag" in {
      val cases = List(
        ("urn", true, false, false),
        ("URN", true, false, false),
        ("https", false, true, false),
        ("HTTPS", false, true, false),
        ("http", false, false, true),
        ("HTTP", false, false, true)
      )
      forAll(cases) {
        case (string, isUrn, isHttps, isHttp) =>
          val scheme = Scheme(string).rightValue
          scheme.isUrn shouldEqual isUrn
          scheme.isHttps shouldEqual isHttps
          scheme.isHttp shouldEqual isHttp
      }
    }

    val normalized = Scheme("HtTpS").rightValue

    "normalize input during construction" in {
      normalized.value shouldEqual "https"
    }
    "show" in {
      normalized.show shouldEqual "https"
    }
    "eq" in {
      Eq.eqv(Scheme("https").rightValue, normalized) shouldEqual true
    }
  }
}
