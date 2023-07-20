package ch.epfl.bluebrain.nexus.tests

import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import io.circe.Json

object SchemaPayload {

  def loadSimple(targetClass: String = "nxv:TestResource"): Json =
    jsonContentOf("/kg/schemas/simple-schema.json", "targetClass" -> targetClass)

}
