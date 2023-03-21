package ch.epfl.bluebrain.nexus.delta.sdk.permissions.model

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

class PermissionsStateSpec extends AnyWordSpecLike with Matchers {

  "A PermissionsState" when {

    val minimum    = Set(Permission.unsafe("my/permission"))
    val additional = Set(Permission.unsafe("my/additional"))

    "initial" should {
      "return its resource representation" in {
        PermissionsState.initial(minimum).toResource(minimum) shouldEqual PermissionsGen.resourceFor(minimum, rev = 0)
      }
    }

    "current" should {
      "return its resource representation" in {
        val current = PermissionsState(
          3,
          additional,
          Instant.EPOCH,
          Identity.Anonymous,
          Instant.EPOCH,
          Identity.Anonymous
        )
        current.toResource(minimum) shouldEqual PermissionsGen.resourceFor(minimum ++ additional, rev = 3)
      }
    }
  }

}
