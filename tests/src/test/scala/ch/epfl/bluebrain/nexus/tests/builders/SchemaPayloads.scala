package ch.epfl.bluebrain.nexus.tests.builders

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import io.circe.Json
object SchemaPayloads {
  def withPowerLevelShape(id: String, maxPowerLevel: Int): IO[Json] = {
    ioJsonContentOf("/kg/schemas/schema-with-power-level.json", "id" -> id, "maxPowerLevel" -> maxPowerLevel)
  }

  def withImportOfPowerLevelShape(id: String, importedSchemaId: String): IO[Json] = {
    ioJsonContentOf("/kg/schemas/schema-that-imports-power-level.json", "id" -> id, "import" -> importedSchemaId)
  }

  def withMinCount(id: String, minCount: Int): IO[Json] = {
    ioJsonContentOf("/kg/schemas/schema.json", "id" -> id, "minCount" -> minCount)
  }
}
