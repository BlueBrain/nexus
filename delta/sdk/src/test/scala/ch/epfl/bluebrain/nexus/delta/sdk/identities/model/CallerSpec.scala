package ch.epfl.bluebrain.nexus.delta.sdk.identities.model

import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.testkit.scalatest.BaseSpec

class CallerSpec extends BaseSpec {

  "A Caller" should {
    "append the subject to the identities set" in {
      val caller = Caller(Identity.Anonymous, Set.empty)
      caller.subject shouldEqual Identity.Anonymous
      caller.identities shouldEqual Set(Identity.Anonymous)
    }
  }

}
