package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec

class ComponentSpec extends RdfSpec {

  "A R or Q Component" should {
    "be constructed successfully" in {
      val cases = List(
        "a"                     -> "a",
        "a%C2%A3/?:@;&b%C3%86c" -> "a£/?:@;&bÆc",
        "a£/?:@;&bÆc"           -> "a£/?:@;&bÆc"
      )
      forAll(cases) {
        case (str, expected) =>
          Component(str).rightValue.value shouldEqual expected
      }
    }
    "fail to construct" in {
      val strings = List("", "asd?=", "asd?+", "asd?=a", "asd?+a")
      forAll(strings) { s =>
        Component(s).leftValue
      }
    }
    "show" in {
      Component("a%C2%A3/?:@;&b%C3%86c").rightValue.show shouldEqual "a£/?:@;&bÆc"
    }
    "eq" in {
      val lhs = Component("a%C2%A3/?:@;&b%C3%86c").rightValue
      val rhs = Component("a£/?:@;&bÆc").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
  }
}
