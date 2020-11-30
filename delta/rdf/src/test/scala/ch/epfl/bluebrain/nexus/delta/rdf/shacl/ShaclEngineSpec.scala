package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ShaclEngineSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues with EitherValuable {

  "A ShaclEngine" should {

    val schema           = jsonContentOf("shacl/schema.json")
    val resource         = jsonContentOf("shacl/resource.json")
    val shaclResolvedCtx = jsonContentOf("contexts/shacl.json")

    implicit val rcr: RemoteContextResolution = RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

    val schemaGraph   = Graph(ExpandedJsonLd(schema).accepted).rightValue
    val resourceGraph = Graph(ExpandedJsonLd(resource).accepted).rightValue

    "validate data" in {
      val report = ShaclEngine(resourceGraph.model, schemaGraph.model, reportDetails = true).accepted
      report.isValid() shouldEqual true
    }

    "validate shapes" in {
      ShaclEngine(schemaGraph.model, reportDetails = true).accepted.isValid() shouldEqual true
    }

    "fail validating shapes if unexpected field value" in {
      val wrongSchema = schema.replace("minCount" -> 1, "wrong")
      val graph       = Graph(ExpandedJsonLd(wrongSchema).accepted).rightValue
      ShaclEngine(graph.model, reportDetails = true).accepted.isValid() shouldEqual false
    }

    "fail validating data if not matching nodes" in {
      val resourceChangedType = resource.replace(keywords.tpe -> "Custom", "Other")
      val resourceGraph       = Graph(ExpandedJsonLd(resourceChangedType).accepted).rightValue
      val report              = ShaclEngine(resourceGraph.model, schemaGraph.model, reportDetails = true).accepted
      report.isValid() shouldEqual false
      report.targetedNodes shouldEqual 0
    }

    "fail validating data if wrong field type" in {
      val resourceChangedNumber = resource.replace("number" -> 24, "Other")
      val resourceGraph         = Graph(ExpandedJsonLd(resourceChangedNumber).accepted).rightValue
      ShaclEngine(resourceGraph.model, schemaGraph.model, reportDetails = true).accepted shouldEqual
        ValidationReport(false, 10, jsonContentOf("shacl/failed_number.json"))
    }
  }

}
