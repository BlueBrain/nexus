package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.schemas
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ResourceFSpec extends AnyWordSpecLike with Matchers {

  "A ResourceF" should {
    val resource = ResourceF(
      1,
      0L,
      Set.empty,
      deprecated = false,
      Instant.EPOCH,
      Identity.Anonymous,
      Instant.EPOCH,
      Identity.Anonymous,
      Latest(schemas.permissions),
      1
    )
    "map its value" in {
      resource.map(_.toString) shouldEqual ResourceF(
        1,
        0L,
        Set.empty,
        deprecated = false,
        Instant.EPOCH,
        Identity.Anonymous,
        Instant.EPOCH,
        Identity.Anonymous,
        Latest(schemas.permissions),
        "1"
      )
    }
  }

}
