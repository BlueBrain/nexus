package ch.epfl.bluebrain.nexus.delta.sdk.model.identities

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class CallerSpec extends AnyWordSpecLike with Matchers {

  "A Caller" should {
    "append the subject to the identities set" in {
      val caller = Caller.unsafe(Identity.Anonymous, Set.empty)
      caller.subject shouldEqual Identity.Anonymous
      caller.identities shouldEqual Set(Identity.Anonymous)
    }
  }

}
