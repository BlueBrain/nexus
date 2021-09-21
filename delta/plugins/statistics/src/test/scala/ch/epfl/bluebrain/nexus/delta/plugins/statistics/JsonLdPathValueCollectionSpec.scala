package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection.{JsonLdProperties, JsonLdRelationships}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, IOValues, TestHelpers}
import io.circe.syntax._
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdPathValueCollectionSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with IOValues
    with OptionValues
    with ContextFixtures
    with CirceEq {
  "A collection of JsonLdPathValue" should {
    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient
    val input                         = jsonContentOf("reconstructed-cell.json")
    val expanded                      = ExpandedJsonLd(input).accepted

    "be generated from expanded Json resource" in {
      val id            = iri"http://api.brain-map.org/api/v2/data/Structure/733"
      val properties    = JsonLdProperties.fromExpanded(expanded)
      val relationships = properties.values.find(_.metadata.id.contains(id)).value
      JsonLdPathValueCollection(
        properties,
        JsonLdRelationships(Seq(relationships))
      ).asJson should equalIgnoreArrayOrder(jsonContentOf("reconstructed-cell-paths.json"))
    }
  }

}
