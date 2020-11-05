package ch.epfl.bluebrain.nexus.tests.kg

import ch.epfl.bluebrain.nexus.testkit.TestHelpers
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import io.circe.Json
import org.scalatest.matchers.should.Matchers

class KgDsl(config: TestsConfig) extends TestHelpers with Matchers {

  def projectJson(path: String = "/kg/projects/project.json", name: String = genString()): Json = {
    val base = s"${config.deltaUri.toString()}/resources/$name/_/"
    jsonContentOf(path, "name" -> name, "base" -> base)
  }

}
