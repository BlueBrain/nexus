package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.*
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import io.circe.Json

class ValidateShaclSuite extends NexusSuite {

  implicit val api: JsonLdApi = TitaniumJsonLdApi.strict

  private val schema           = jsonContentOf("shacl/schema.json")
  private val data             = jsonContentOf("shacl/resource.json")
  private val shaclResolvedCtx = jsonContentOf("contexts/shacl.json").topContextValueOrEmpty

  implicit private val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  private val shaclValidation = ValidateShacl(rcr).accepted

  private val schemaGraph = toGraph(schema).accepted
  private val dataGraph   = toGraph(data).accepted

  private def toGraph(json: Json) = ExpandedJsonLd(json).flatMap(_.toGraph)

  test("Validate data from schema model") {
    shaclValidation(dataGraph, schemaGraph, reportDetails = true).assert(_.conformsWithTargetedNodes)
  }

  test("Fail validating data if not matching nodes") {
    val dataChangedType = data.replace(keywords.tpe -> "Custom", "Other")
    for {
      resourceGraph <- toGraph(dataChangedType)
      _             <- shaclValidation(resourceGraph, schemaGraph, reportDetails = true).assert { report =>
                         !report.conformsWithTargetedNodes && report.targetedNodes == 0
                       }
    } yield ()
  }

  test("Fail validating data if wrong field type") {
    val dataInvalidNumber = data.replace("number" -> 24, "Other")
    val detailedOutput    = jsonContentOf("shacl/failed_number.json")
    val expectedReport    = ValidationReport.unsafe(conforms = false, 10, detailedOutput)
    for {
      wrongGraph <- toGraph(dataInvalidNumber)
      _          <- shaclValidation(wrongGraph, schemaGraph, reportDetails = true).assertEquals(expectedReport)
    } yield ()
  }

  test("Validate shapes") {
    shaclValidation(schemaGraph, reportDetails = true).assert(_.conformsWithTargetedNodes)
  }

  test("Fail validating shapes if no property is defined") {
    val wrongSchema = schema.mapAllKeys("property", _ => Json.obj())

    toGraph(wrongSchema).flatMap { wrongGraph =>
      shaclValidation(wrongGraph, reportDetails = true).assert(
        _.conforms == false,
        "Validation should fail as property requires at least one element"
      )
    }
  }

}
