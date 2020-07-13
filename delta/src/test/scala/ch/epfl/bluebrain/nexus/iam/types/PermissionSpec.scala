package ch.epfl.bluebrain.nexus.iam.types

import ch.epfl.bluebrain.nexus.util.Randomness
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Random

class PermissionSpec extends AnyWordSpecLike with Matchers with Randomness {
  "A Permission" should {
    "be constructed correctly for valid strings" in {
      for (_ <- 1 to 100) {
        val valid = genValid
        Permission(valid) shouldEqual Some(Permission.unsafe(valid))
      }
    }

    "fail to construct for illegal strings" in {
      Permission("") shouldEqual None
      Permission("1") shouldEqual None
      Permission("1abd") shouldEqual None
      Permission("_abd") shouldEqual None
      Permission("foö") shouldEqual None
      Permission("bar*") shouldEqual None
      Permission(genString(33)) shouldEqual None
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
