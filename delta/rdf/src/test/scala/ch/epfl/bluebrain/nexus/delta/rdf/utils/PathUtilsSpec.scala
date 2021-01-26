package ch.epfl.bluebrain.nexus.delta.rdf.utils

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._

import java.nio.file.Paths

class PathUtilsSpec extends AnyWordSpecLike with Matchers {
  "A Path" should {
    val `/tmp/a`     = Paths.get("/tmp/a")
    val `/tmp/a/b/c` = Paths.get("/tmp/a/b/c")
    val `/tmp`       = Paths.get("/tmp")

    "be descendant of another path" in {
      `/tmp/a/b/c`.descendantOf(`/tmp/a`) shouldEqual true
      `/tmp/a`.descendantOf(`/tmp`) shouldEqual true
    }

    "not be descendant of another path" in {
      `/tmp`.descendantOf(`/tmp/a`) shouldEqual false
      `/tmp/a`.descendantOf(`/tmp/a`) shouldEqual false
    }
  }

}
