package ai.senscience.nexus.tests.builders

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import io.circe.Json
object SchemaPayloads {
  private val loader                                                = ClasspathResourceLoader()
  def withPowerLevelShape(id: String, maxPowerLevel: Int): IO[Json] = {
    loader.jsonContentOf("kg/schemas/schema-with-power-level.json", "id" -> id, "maxPowerLevel" -> maxPowerLevel)
  }

  def withImportOfPowerLevelShape(id: String, importedSchemaId: String): IO[Json] = {
    loader.jsonContentOf("kg/schemas/schema-that-imports-power-level.json", "id" -> id, "import" -> importedSchemaId)
  }

  def withMinCount(id: String, minCount: Int): IO[Json] = {
    loader.jsonContentOf("kg/schemas/schema.json", "id" -> id, "minCount" -> minCount)
  }

  def simple(id: String): IO[Json] = {
    loader.jsonContentOf("kg/schemas/schema.json", "id" -> id, "minCount" -> 1)
  }
}
