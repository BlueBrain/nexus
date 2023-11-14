package ch.epfl.bluebrain.nexus.tests.kg

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils._
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import io.circe.Json

class KgDsl(config: TestsConfig) {

  def projectJson(path: String = "/kg/projects/project.json", name: String): IO[Json] =
    KgDsl.projectJson(path, name, config)

  def projectJsonWithCustomBase(path: String = "/kg/projects/project.json", name: String, base: String): IO[Json] = {
    ioJsonContentOf(path, "name" -> name, "base" -> base)
  }
}

object KgDsl {
  def projectJson(path: String = "/kg/projects/project.json", name: String, config: TestsConfig): IO[Json] = {
    val base = s"${config.deltaUri.toString()}/resources/$name/_/"
    ioJsonContentOf(path, "name" -> name, "base" -> base)
  }
}
