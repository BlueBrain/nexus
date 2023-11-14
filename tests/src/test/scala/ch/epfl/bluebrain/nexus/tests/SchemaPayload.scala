package ch.epfl.bluebrain.nexus.tests

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils.ioJsonContentOf
import io.circe.Json

object SchemaPayload {

  def loadSimple(targetClass: String = "nxv:TestResource"): IO[Json]     =
    ioJsonContentOf("/kg/schemas/simple-schema.json", "targetClass" -> targetClass)

  def loadSimpleNoId(targetClass: String = "nxv:TestResource"): IO[Json] =
    ioJsonContentOf("/kg/schemas/simple-schema-no-id.json", "targetClass" -> targetClass)

}
