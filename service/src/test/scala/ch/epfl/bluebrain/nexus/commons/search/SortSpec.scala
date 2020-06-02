package ch.epfl.bluebrain.nexus.commons.search

import ch.epfl.bluebrain.nexus.commons.search.Sort.OrderType._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class SortSpec extends AnyWordSpecLike with Matchers {

  "A Sort" should {
    "created correctly " in {
      Sort("createdAtTime") shouldEqual Sort(Asc, s"createdAtTime")
      Sort(s"+createdAtTime") shouldEqual Sort(Asc, s"createdAtTime")
      Sort(s"-type") shouldEqual Sort(Desc, s"type")
    }
  }
}
