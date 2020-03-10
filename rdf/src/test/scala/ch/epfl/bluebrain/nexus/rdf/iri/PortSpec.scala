package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.iri.Authority.Port

class PortSpec extends RdfSpec {

  "A Port" should {
    "be constructed successfully" in {
      val strings = List("0", "1", "10", "65535", "60000")
      forAll(strings) { s => Port(s).rightValue }
    }
    "be range checked" in {
      val ints = List(-1, 65536)
      forAll(ints) { i => Port(i).leftValue }
    }
    "fail to construct" in {
      val strings = List("", "00", "01", "-1", "65536")
      forAll(strings) { s => Port(s).leftValue }
    }
    "show" in {
      Port(1).rightValue.show shouldEqual "1"
    }
    "eq" in {
      Eq.eqv(Port("1").rightValue, Port(1).rightValue) shouldEqual true
    }
  }
}
