package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.IndexLabel.IllegalIndexLabel
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, TestHelpers}
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class IndexLabelSpec extends AnyWordSpecLike with Matchers with Inspectors with TestHelpers with EitherValuable {
  "An IndexLabel" should {
    "fail" in {
      val list = List(".", ".s", "+s", "s*e", "s?e", "s/e", "s|e", "s\\e", "s,e", genString(length = 210))
      forAll(list) { index =>
        IndexLabel(index) shouldEqual Left(IllegalIndexLabel(index))
      }
    }

    "succeed" in {
      val index = genString()
      IndexLabel(index).rightValue.value shouldEqual index
    }
  }
}
