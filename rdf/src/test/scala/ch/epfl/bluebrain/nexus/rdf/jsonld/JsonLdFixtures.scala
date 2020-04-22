package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import io.circe.Json
import ch.epfl.bluebrain.nexus.rdf.syntax.all._

trait JsonLdFixtures extends RdfSpec {
  private val expandFiles = jsonFiles("/jsonld/expand")

  private val exampleBase               = uri"http://example/base/"
  private val example                   = uri"http://example.org/"
  private def defaultBase(name: String) = uri"https://w3c.github.io/json-ld-api/tests/expand/$name"
  private val expandedOptionsBase =
    Map("0089" -> exampleBase, "0090" -> exampleBase, "0091" -> exampleBase, "m005" -> example)

  lazy val expandTestCases: List[((String, Json), (String, Option[Json]), JsonLdOptions)] = expandFiles.view
    .filterKeys(_.endsWith("-in.jsonld"))
    .map {
      case (inName, inJson) =>
        val prefix  = inName.replace("-in.jsonld", "")
        val outName = prefix + "-out.jsonld"
        val outJson = expandFiles.get(outName)
        val opts    = JsonLdOptions(base = Some(expandedOptionsBase.getOrElse(prefix, defaultBase(inName))))
        (inName -> inJson, outName -> outJson, opts)
    }
    .toList
    .sortBy { case ((k, _), _, _) => k }
}
object JsonLdFixtures extends JsonLdFixtures
