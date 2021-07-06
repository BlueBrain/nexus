package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.JsonLdPathValueCollection
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.ExpandedJsonLd
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import io.circe.syntax._

class JsonLdPathValueCollectionSpec extends AnyWordSpecLike with Matchers with TestHelpers with IOValues {
  "A collection of JsonLdPathValue" should {
    implicit val rcr: RemoteContextResolution = RemoteContextResolution.never
    val input                                 = jsonContentOf("reconstructed-cell.json")
    val expanded                              = ExpandedJsonLd(input).accepted

    "be converted to Json" in {
      JsonLdPathValueCollection(expanded).asJson shouldEqual jsonContentOf("reconstructed-cell-paths.json")
    }
  }

}
