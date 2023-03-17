package ch.epfl.bluebrain.nexus.tests.builders

import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import io.circe.Json
object SchemaPayloads {
  def withPowerLevelShape(id: String, maxPowerLevel: Int): Json = {
    jsonContentOf("/kg/schemas/schema-with-power-level.json", "id" -> id, "maxPowerLevel" -> maxPowerLevel)
  }

  def withImportOfPowerLevelShape(id: String, importedSchemaId: String): Json = {
    jsonContentOf("/kg/schemas/schema-that-imports-power-level.json", "id" -> id, "import" -> importedSchemaId)
  }

  def withMinCount(id: String, minCount: Int): Json = {
    jsonContentOf("/kg/schemas/schema.json", "id" -> id, "minCount" -> minCount)
  }
}
