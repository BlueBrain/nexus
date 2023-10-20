package ch.epfl.bluebrain.nexus.delta.sdk.resolvers.model

import ch.epfl.bluebrain.nexus.delta.sdk.error.FormatErrors.ResolverPriorityIntervalError
import ch.epfl.bluebrain.nexus.testkit.scalatest.EitherValues
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PrioritySpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues {

  "A priority" should {

    "be constructed from a valid value" in {
      Priority(42).rightValue.value shouldEqual 42
    }

    "fail for out-of-bounds values" in {
      forAll(List(-15, -1, 1001, 1000000)) { value =>
        Priority(value).leftValue shouldEqual ResolverPriorityIntervalError(value, 0, 1000)
      }
    }
  }

}
