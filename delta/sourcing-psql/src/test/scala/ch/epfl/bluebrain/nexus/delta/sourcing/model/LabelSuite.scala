package ch.epfl.bluebrain.nexus.delta.sourcing.model

import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.testkit.bio.EitherAssertions
import munit.FunSuite

class LabelSuite extends FunSuite with EitherAssertions with TestHelpers {

  test("Construct correctly from alphanumeric chars, - and _") {
    (1 to 64).foreach { length =>
      val string =
        genString(length, Vector.range('a', 'z') ++ Vector.range('0', '9') ++ Vector.range('A', 'Z') :+ '-' :+ '_')
      assertEquals(Label.unsafe(string).value, string)
      Label(string).map(_.value).assertRight(string)
    }
  }

  test("Fail to construct for illegal formats") {
    List("", " ", "a ", " a", "è", "$", "%a", genString(65)).foreach { string =>
      Label(string).assertLeft()
    }
  }

  test("Sanitize invalid characters and truncate") {
    val validChars = genString(65)
    val string     = s"!@#%^&*()$validChars!@#%^&*()"

    Label.sanitized(string).map(_.value).assertRight(validChars.dropRight(1))
  }

  test("Fail to construct if there are no valid characters") {
    val string = s"!@#%^&*()!@#%^&*()"
    Label(string).assertLeft()
  }

}
