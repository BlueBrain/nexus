package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.IllegalNameFormatError
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

class NameSpec extends BaseSpec {

  "A Name" should {
    "be constructed correctly from alphanumeric chars, - and _" in {
      forAll(1 to 128) { length =>
        val string = genString(
          length,
          Vector.range('a', 'z') ++ Vector.range('0', '9') ++ Vector.range('A', 'Z') :+ '-' :+ '_' :+ ' '
        )
        Name.unsafe(string).value shouldEqual string
        Name(string).rightValue.value shouldEqual string
      }
    }
    "fail to construct for illegal formats" in {
      val cases = List("", "a ^", "è", "$", "%a", genString(129))
      forAll(cases) { string =>
        Name(string).leftValue shouldBe a[IllegalNameFormatError]
      }
    }
  }

}
