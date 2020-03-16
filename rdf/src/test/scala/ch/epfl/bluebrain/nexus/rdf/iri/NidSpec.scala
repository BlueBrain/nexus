package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import io.circe.Json
import io.circe.syntax._

class NidSpec extends RdfSpec {

  "A Nid" should {
    "be constructed successfully" in {
      val strings = List("aa", "a-a", "1a", "11", "AA", s"a${List.fill(30)("1").mkString}a")
      forAll(strings) { s => Nid(s).rightValue }
    }
    "fail to construct" in {
      val strings = List("", "-a", "a-", "a", "%20a", "-", s"a${List.fill(31)("1").mkString}a")
      forAll(strings) { s => Nid(s).leftValue }
    }
    val normalized = Nid("IbAn")

    "normalize input during construction" in {
      normalized.rightValue.value shouldEqual "iban"
    }
    "show" in {
      normalized.rightValue.show shouldEqual "iban"
    }
    "eq" in {
      Eq.eqv(Nid("iban").rightValue, normalized.rightValue) shouldEqual true
    }
    "encode" in {
      val name = "iban"
      Nid(name).rightValue.asJson shouldEqual Json.fromString(name)
    }
    "decode" in {
      val name = "iban"
      Json.fromString(name).as[Nid].rightValue shouldEqual Nid(name).rightValue
    }
  }
}
