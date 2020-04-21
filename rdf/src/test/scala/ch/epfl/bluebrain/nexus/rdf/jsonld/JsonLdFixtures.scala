package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import io.circe.Json

trait JsonLdFixtures extends RdfSpec {
  private val expandFiles       = jsonFiles("/jsonld/expand")

  lazy val expandTestCases: List[((String, Json), (String, Option[Json]))] = expandFiles.view
    .filterKeys(_.endsWith("-in.jsonld"))
    .map {
      case (inName, inJson) =>
        val prefix  = inName.replace("-in.jsonld", "")
        val outName = prefix + "-out.jsonld"
        (inName -> inJson, outName -> expandFiles.get(outName))
    }
    .toList
    .sortBy { case ((k, _), _) => k }
}
object JsonLdFixtures extends JsonLdFixtures
