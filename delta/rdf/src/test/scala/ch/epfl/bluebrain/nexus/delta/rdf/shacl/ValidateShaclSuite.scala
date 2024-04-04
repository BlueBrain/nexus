package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

class ValidateShaclSuite extends NexusSuite {

  implicit val api: JsonLdApi = JsonLdJavaApi.lenient

  private val schema           = jsonContentOf("shacl/schema.json")
  private val data             = jsonContentOf("shacl/resource.json")
  private val shaclResolvedCtx = jsonContentOf("contexts/shacl.json").topContextValueOrEmpty

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  private val shaclValidation = ValidateShacl(rcr).accepted

  private val schemaGraph = toGraph(schema).accepted
  private val dataGraph   = toGraph(data).accepted

  private def toGraph(json: Json) = ExpandedJsonLd(json).map(_.toGraph).rethrow

  test("Validate data from schema model") {
    shaclValidation(dataGraph, schemaGraph, reportDetails = true).assert(_.isValid())
  }

  test("Fail validating data if not matching nodes") {
    val dataChangedType = data.replace(keywords.tpe -> "Custom", "Other")
    for {
      resourceGraph <- toGraph(dataChangedType)
      _             <- shaclValidation(resourceGraph, schemaGraph, reportDetails = true).assert { report =>
                         !report.isValid() && report.targetedNodes == 0
                       }
    } yield ()
  }

  test("Fail validating data if wrong field type") {
    val dataInvalidNumber = data.replace("number" -> 24, "Other")
    val detailedOutput    = jsonContentOf("shacl/failed_number.json")
    val expectedReport    = ValidationReport(conforms = false, 10, detailedOutput)
    for {
      wrongGraph <- toGraph(dataInvalidNumber)
      _          <- shaclValidation(wrongGraph, schemaGraph, reportDetails = true).assertEquals(expectedReport)
    } yield ()
  }

  test("Validate shapes") {
    shaclValidation(schemaGraph, reportDetails = true).assert(_.isValid())
  }

  test("Fail validating shapes if unexpected field value") {
    val wrongSchema = schema.replace("minCount" -> 1, "wrong")
    for {
      wrongGraph <- toGraph(wrongSchema)
      _          <- shaclValidation(wrongGraph, reportDetails = true).assert(_.isValid() == false)
    } yield ()
  }

}
