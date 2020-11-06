package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.generators.PermissionsGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsStateSpec extends AnyWordSpecLike with Matchers {

  "A PermissionsState" when {

    val minimum    = Set(Permission.unsafe("my/permission"))
    val additional = Set(Permission.unsafe("my/additional"))

    "initial" should {
      "return its resource representation" in {
        PermissionsState.Initial.toResource(minimum) shouldEqual PermissionsGen.resourceFor(minimum, rev = 0L)
      }
    }

    "current" should {
      "return its resource representation" in {
        val current = PermissionsState.Current(
          3L,
          additional,
          Instant.EPOCH,
          Identity.Anonymous,
          Instant.EPOCH,
          Identity.Anonymous
        )
        current.toResource(minimum) shouldEqual PermissionsGen.resourceFor(minimum ++ additional, rev = 3L)
      }
    }
  }

}
