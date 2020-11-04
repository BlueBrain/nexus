package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.JsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import io.circe.syntax._

class ShaclEngineSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues {

  "A ShaclEngine" should {

    val schema           = jsonContentOf("shacl/schema.json")
    val resource         = jsonContentOf("shacl/resource.json")
    val shaclResolvedCtx = jsonContentOf("contexts/shacl-context.json")

    implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

    val schemaGraph   = Graph(JsonLd.expand(schema).accepted).accepted
    val resourceGraph = Graph(JsonLd.expand(resource).accepted).accepted

    "validate" in {
      val report =
        ShaclEngine(resourceGraph.model, schemaGraph.model, validateShapes = true, reportDetails = true).accepted
      report.isValid() shouldEqual true
    }

    "fail validation when not matching nodes" in {
      val resourceChangedType = resource.replace(keywords.tpe -> "Custom".asJson, "Other".asJson)
      val resourceGraph       = Graph(JsonLd.expand(resourceChangedType).accepted).accepted
      val report              =
        ShaclEngine(resourceGraph.model, schemaGraph.model, validateShapes = true, reportDetails = true).accepted
      report.isValid() shouldEqual false
      report.targetedNodes shouldEqual 0
    }

    "fail validation when wrong field type" in {
      val resourceChangedNumber = resource.replace("number" -> 24.asJson, "Other".asJson)
      val resourceGraph         = Graph(JsonLd.expand(resourceChangedNumber).accepted).accepted
      ShaclEngine(
        resourceGraph.model,
        schemaGraph.model,
        validateShapes = true,
        reportDetails = true
      ).accepted shouldEqual
        ValidationReport(false, 10, jsonContentOf("shacl/failed_number.json"))
    }
  }

}
