package ch.epfl.bluebrain.nexus.delta.sdk.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatError.IllegalPermissionFormatError
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Random

class PermissionSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelpers with EitherValuable {

  "A Permission" should {
    "be constructed correctly for valid strings" in {
      for (_ <- 1 to 100) {
        val valid = genValid
        Permission(valid).rightValue shouldEqual Permission.unsafe(valid)
      }
    }

    "fail to construct for illegal strings" in {
      forAll(List("", " ", "1", "1abd", "_abd", "foö", "bar*", genString(33))) { string =>
        Permission(string).leftValue shouldBe an[IllegalPermissionFormatError]
      }
    }
  }

  private def genValid: String = {
    val lower   = 'a' to 'z'
    val upper   = 'A' to 'Z'
    val numbers = '0' to '9'
    val symbols = List('-', '_', ':', '\\', '/')
    val head    = genString(1, lower ++ upper)
    val tail    = genString(Random.nextInt(32), lower ++ upper ++ numbers ++ symbols)
    head + tail
  }

}
