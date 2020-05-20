package ch.epfl.bluebrain.nexus.realms

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import ch.epfl.bluebrain.nexus.util.{EitherValues, Randomness}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class RealmLabelSpec extends AnyWordSpecLike with Matchers with Randomness with Inspectors with EitherValues {

  "A Label" should {
    "be constructed correctly from alphanumeric chars" in {
      forAll(1 to 32) { length =>
        val string = genString(length, Vector.range('a', 'z') ++ Vector.range('0', '9'))
        RealmLabel.unsafe(string).value shouldEqual string
        RealmLabel(string).rightValue.value shouldEqual string
      }
    }
    "fail to construct for illegal formats" in {
      val cases = List("", " ", "a ", " a", "a-", "_")
      forAll(cases) { string =>
        intercept[IllegalArgumentException](RealmLabel.unsafe(string))
        RealmLabel(string).leftValue shouldEqual s"RealmLabel '$string' does not match pattern '${RealmLabel.regex.regex}'"
      }
    }
    "return its path representation" in {
      RealmLabel.unsafe("abc").rootedPath shouldEqual Path("/abc")
    }
    "return an iri representation" in {
      forAll(List(Uri("http://localhost"), Uri("http://localhost/"))) { base =>
        val label = RealmLabel.unsafe("abc")
        label.toUri(base) shouldEqual Uri("http://localhost/abc")
      }
    }
  }

}
