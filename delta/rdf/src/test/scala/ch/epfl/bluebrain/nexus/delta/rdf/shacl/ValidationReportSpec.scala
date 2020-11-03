package ch.epfl.bluebrain.nexus.delta.rdf.shacl

import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import io.circe.Json
import io.circe.syntax._
import org.apache.jena.rdf.model.{ModelFactory, Resource}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{Lang, RDFParser}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ValidationReportSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with OptionValues
    with IOValues {

  private def resource(json: Json): Resource = {
    val m = ModelFactory.createDefaultModel
    RDFParser.create.fromString(json.noSpaces).base("").lang(Lang.JSONLD).parse(StreamRDFLib.graph(m.getGraph))
    m.createResource()
  }

  "A ValidationReport" should {
    val ctx      = jsonContentOf("/shacl-context-resp.json")
    val conforms = jsonContentOf("/shacl/conforms.json")
    val failed   = jsonContentOf("/shacl/failed.json")

    "be constructed correctly when conforms" in {
      ValidationReport(resource(conforms deepMerge ctx)).accepted shouldEqual
        ValidationReport(conforms = true, 1, conforms)
    }

    "be constructed correctly when fails" in {
      val report = ValidationReport(resource(failed deepMerge ctx)).accepted
      report.conforms shouldEqual false
      report.targetedNodes shouldEqual 1
      report.isValid() shouldEqual false
      val array  = report.json.hcursor.downField("result").downField("detail").focus.flatMap(_.asArray).value
      array.map(_.hcursor.get[String]("resultMessage").rightValue).sorted shouldEqual Vector(
        "Focus node has 2^^http://www.w3.org/2001/XMLSchema#integer of the shapes from the 'exactly one' list",
        "Value does not have shape http://localhost/v0/schemas/nexus/schemaorg/quantitativevalue/v0.1.0/shapes/QuantitativeValueShape"
      ).sorted
    }

    "be encoded as json" in {
      val report = ValidationReport(resource(failed deepMerge ctx)).accepted
      report.asJson shouldEqual report.json
    }
  }
}
