package ch.epfl.bluebrain.nexus.tests.kg

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.tests.config.TestsConfig
import ch.epfl.bluebrain.nexus.tests.kg.KgDsl.loader
import io.circe.Json

class KgDsl(config: TestsConfig) {

  def projectJson(path: String = "/kg/projects/project.json", name: String): IO[Json] =
    KgDsl.projectJson(path, name, config)

  def projectJsonWithCustomBase(path: String = "/kg/projects/project.json", name: String, base: String): IO[Json] = {
    loader.jsonContentOf(path, "name" -> name, "base" -> base)
  }
}

object KgDsl {
  private val loader = ClasspathResourceLoader()
  def projectJson(path: String = "/kg/projects/project.json", name: String, config: TestsConfig): IO[Json] = {
    val base = s"${config.deltaUri.toString()}/resources/$name/_/"
    loader.jsonContentOf(path, "name" -> name, "base" -> base)
  }
}
