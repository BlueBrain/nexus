package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax._
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.query.DatasetFactory
import org.apache.jena.rdf.model.Resource

class ValidationReportSpec extends CatsEffectSpec {

  implicit val api: JsonLdApi = JsonLdJavaApi.strict

  private val shaclResolvedCtx = jsonContentOf("contexts/shacl.json").topContextValueOrEmpty

  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.shacl -> shaclResolvedCtx)

  private def resource(json: Json): Resource = {
    val g = Graph(ExpandedJsonLd(json).accepted).accepted.value
    DatasetFactory.wrap(g).getDefaultModel.createResource()
  }

  "A ValidationReport" should {
    val conforms = jsonContentOf("shacl/conforms.json")
    val failed   = jsonContentOf("shacl/failed.json")

    "be constructed correctly when conforms" in {
      ValidationReport(resource(conforms)).accepted shouldEqual
        ValidationReport(conforms = true, 1, conforms)
    }

    "be constructed correctly when fails" in {
      val report = ValidationReport(resource(failed)).accepted
      report.conforms shouldEqual false
      report.targetedNodes shouldEqual 1
      report.conformsWithTargetedNodes shouldEqual false
      val array  = report.json.hcursor.downField("result").downField("detail").focus.flatMap(_.asArray).value
      array.map(_.hcursor.get[String]("resultMessage").rightValue).sorted shouldEqual Vector(
        "Focus node has 2^^http://www.w3.org/2001/XMLSchema#integer of the shapes from the 'exactly one' list",
        "Value does not have shape http://localhost/v0/schemas/nexus/schemaorg/quantitativevalue/v0.1.0/shapes/QuantitativeValueShape"
      ).sorted
    }

    "be encoded as json" in {
      val report = ValidationReport(resource(failed)).accepted
      report.asJson shouldEqual report.json
    }
  }
}
