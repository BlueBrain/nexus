package ch.epfl.bluebrain.nexus.delta.kernel.utils

import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import io.circe.Json
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SecretSpec extends AnyWordSpecLike with Matchers with OptionValues {

  "A Secret" should {

    "not expose its value when calling toString" in {
      Secret("value").toString shouldEqual "SECRET"
    }

    "be converted to Json" in {
      Secret("value").asJson shouldEqual Json.Null
    }

    "be extracted from Json" in {
      val json = "value".asJson
      json.as[Secret[String]] shouldEqual Right(Secret("value"))
    }

  }

}
