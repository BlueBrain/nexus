package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection.{JsonLdProperties, JsonLdRelationships}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class JsonLdPathValueCollectionSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues {
  "A collection of JsonLdPathValue" should {
    implicit val rcr: RemoteContextResolution = RemoteContextResolution.never
    val input                                 = jsonContentOf("reconstructed-cell.json")
    val expanded                              = ExpandedJsonLd(input).accepted

    "be generated from expanded Json resource" in {
      val properties = JsonLdProperties.fromExpanded(expanded)
      JsonLdPathValueCollection(properties, JsonLdRelationships.empty).asJson shouldEqual
        jsonContentOf("reconstructed-cell-paths.json")
    }
  }

}
