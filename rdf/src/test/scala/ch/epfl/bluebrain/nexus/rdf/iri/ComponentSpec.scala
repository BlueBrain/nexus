package ch.epfl.bluebrain.nexus.rdf.iri

import cats.Eq
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import io.circe.Json
import io.circe.syntax._

class ComponentSpec extends RdfSpec {

  "A R or Q Component" should {
    val values = List(
      "a"                     -> "a",
      "a%C2%A3/?:@;&b%C3%86c" -> "a£/?:@;&bÆc",
      "a£/?:@;&bÆc"           -> "a£/?:@;&bÆc"
    )
    "be constructed successfully" in {
      forAll(values) {
        case (str, expected) =>
          Component(str).rightValue.value shouldEqual expected
      }
    }
    "fail to construct" in {
      val strings = List("", "asd?=", "asd?+", "asd?=a", "asd?+a")
      forAll(strings) { s => Component(s).leftValue }
    }
    "show" in {
      Component("a%C2%A3/?:@;&b%C3%86c").rightValue.show shouldEqual "a£/?:@;&bÆc"
    }
    "eq" in {
      val lhs = Component("a%C2%A3/?:@;&b%C3%86c").rightValue
      val rhs = Component("a£/?:@;&bÆc").rightValue
      Eq.eqv(lhs, rhs) shouldEqual true
    }
    "encode" in {
      forAll(values) {
        case (cons, string) => Component(cons).rightValue.asJson shouldEqual Json.fromString(string)
      }
    }
    "decode" in {
      forAll(values) {
        case (cons, string) => Json.fromString(string).as[Component].rightValue shouldEqual Component(cons).rightValue
      }
    }
  }
}
