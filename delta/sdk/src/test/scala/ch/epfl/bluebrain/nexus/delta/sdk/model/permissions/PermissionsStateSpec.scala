package ch.epfl.bluebrain.nexus.delta.sdk.model.permissions

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PermissionsStateSpec extends AnyWordSpecLike with Matchers {

  "A PermissionsState" when {
    val id         = nxv.Permissions
    val minimum    = Set(Permission.unsafe("my/permission"))
    val additional = Set(Permission.unsafe("my/additional"))
    "initial" should {
      "return its resource representation" in {
        PermissionsState.Initial.toResource(id, minimum) shouldEqual ResourceF(
          id,
          0L,
          Set(nxv.Permissions),
          deprecated = false,
          Instant.EPOCH,
          Identity.Anonymous,
          Instant.EPOCH,
          Identity.Anonymous,
          Latest(schemas.permissions),
          minimum
        )
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
        current.toResource(id, minimum) shouldEqual ResourceF(
          id,
          3L,
          Set(nxv.Permissions),
          deprecated = false,
          Instant.EPOCH,
          Identity.Anonymous,
          Instant.EPOCH,
          Identity.Anonymous,
          Latest(schemas.permissions),
          minimum ++ additional
        )
      }
    }
  }

}
