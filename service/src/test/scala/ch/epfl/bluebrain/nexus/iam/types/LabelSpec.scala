package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.rdf.Iri.{Path, Url}
import ch.epfl.bluebrain.nexus.util.{EitherValues, Randomness}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LabelSpec extends AnyWordSpecLike with Matchers with Randomness with Inspectors with EitherValues {

  "A Label" should {
    "be constructed correctly from alphanumeric chars" in {
      forAll(1 to 32) { length =>
        val string = genString(length, Vector.range('a', 'z') ++ Vector.range('0', '9'))
        Label.unsafe(string).value shouldEqual string
        Label(string).rightValue.value shouldEqual string
      }
    }
    "fail to construct for illegal formats" in {
      val cases = List("", " ", "a ", " a", "a-", "_")
      forAll(cases) { string =>
        intercept[IllegalArgumentException](Label.unsafe(string))
        Label(string).leftValue shouldEqual s"Label '$string' does not match pattern '${Label.regex.regex}'"
      }
    }
    "return its path representation" in {
      Label.unsafe("abc").toPath shouldEqual Path("/abc").rightValue
    }
    "return an iri representation" in {
      forAll(List("http://localhost", "http://localhost/")) { str =>
        val base  = Url(str).rightValue
        val label = Label.unsafe("abc")
        label.toIri(base) shouldEqual Url("http://localhost/abc").rightValue
      }
    }
  }

}
