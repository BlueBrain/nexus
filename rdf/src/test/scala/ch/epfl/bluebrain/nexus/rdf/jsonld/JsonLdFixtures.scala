package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.RdfSpec
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.Json

import scala.util.Try

trait JsonLdFixtures extends RdfSpec {
  private def defaultBase(name: String) = uri"https://w3c.github.io/json-ld-api/tests/$name"

  lazy val expandTestCases: List[(String, Json, String, Option[Json], JsonLdOptions)] = {
    val exampleBase = uri"http://example/base/"
    val example     = uri"http://example.org/"
    val baseMap     = Map("0089" -> exampleBase, "0090" -> exampleBase, "0091" -> exampleBase, "m005" -> example)
    val files       = jsonFiles("/jsonld/expand")
    files.view
      .filterKeys(_.endsWith("-in.jsonld"))
      .map {
        case (inName, inJson) =>
          val prefix  = inName.replace("-in.jsonld", "")
          val outName = prefix + "-out.jsonld"
          val outJson = files.get(outName)
          val opts    = JsonLdOptions(base = Some(baseMap.getOrElse(prefix, defaultBase(s"expand/$inName"))))
          (inName, inJson, outName, outJson, opts)
      }
      .toList
      .sortBy { case (k, _, _, _, _) => k }
  }

  lazy val toRdfTestCases: List[(String, Json, String, Option[String], JsonLdOptions)] = {
    val exampleBase    = uri"http://example/base/"
    val exampleBaseOrg = uri"http://example.org/"
    val baseMap = Map(
      "e076" -> exampleBase,
      "e089" -> exampleBase,
      "e090" -> exampleBase,
      "e091" -> exampleBase,
      "m005" -> exampleBaseOrg
    )
    jsonFiles("/jsonld/toRdf", _.getName.endsWith(".jsonld"))
      .map {
        case (inName, inJson) =>
          val prefix    = inName.replace("-in.jsonld", "")
          val outName   = prefix + "-out.nq"
          val outString = Try(contentOf(s"/jsonld/toRdf/$outName")).toOption
          val opts      = JsonLdOptions(base = Some(baseMap.getOrElse(prefix, defaultBase(s"toRdf/$inName"))))
          (inName, inJson, outName, outString, opts)
      }
      .toList
      .sortBy { case (k, _, _, _, _) => k }
  }
}
object JsonLdFixtures extends JsonLdFixtures
