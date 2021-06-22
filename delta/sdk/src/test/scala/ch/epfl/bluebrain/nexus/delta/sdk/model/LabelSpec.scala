package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalLabelFormatError
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class LabelSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelpers with EitherValuable {

  "A Label" should {
    "be constructed correctly from alphanumeric chars, - and _" in {
      forAll(1 to 64) { length =>
        val string =
          genString(length, Vector.range('a', 'z') ++ Vector.range('0', '9') ++ Vector.range('A', 'Z') :+ '-' :+ '_')
        Label.unsafe(string).value shouldEqual string
        Label(string).rightValue.value shouldEqual string
      }
    }
    "fail to construct for illegal formats" in {
      val cases = List("", " ", "a ", " a", "è", "$", "%a", genString(65))
      forAll(cases) { string =>
        Label(string).leftValue shouldBe a[IllegalLabelFormatError]
      }
    }

    "should sanitize invalid characters and truncate" in {
      val validChars = genString(65)
      val string     = s"!@#%^&*()$validChars!@#%^&*()"
      Label.sanitized(string).rightValue.value shouldEqual validChars.dropRight(1)
    }

    "fail to construct if there are no valid characters" in {
      val string = s"!@#%^&*()!@#%^&*()"
      Label.sanitized(string).leftValue shouldBe a[IllegalLabelFormatError]
    }
  }

}
