package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.client

import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class DeltaNamespaceSetSpec extends AnyWordSpecLike with EitherValuable with Matchers with TestHelpers {

  "Delta namespaces" should {
    val response = jsonContentOf("namespace-list.json")

    val expected = DeltaNamespaceSet(Set("delta_01_6", "delta_02_3"))

    "be correctly decoded" in {
      response.as[DeltaNamespaceSet].rightValue shouldEqual expected
    }
  }

}
