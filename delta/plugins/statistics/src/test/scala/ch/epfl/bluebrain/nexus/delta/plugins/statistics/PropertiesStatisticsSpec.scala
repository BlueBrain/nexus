package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PropertiesStatisticsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with IOValues {

  "PropertiesStatistics" should {
    implicit val rcr: RemoteContextResolution =
      RemoteContextResolution.fixed {
        (contexts + "properties.json") -> jsonContentOf("contexts/properties.json").topContextValueOrEmpty
      }

    val responseJson = jsonContentOf("paths-properties-aggregations-response.json")
    val expected     = jsonContentOf("properties-tree.json")

    "be converted from Elasticsearch response to client response" in {
      implicit val d = propertiesDecoderFromEsAggregations(iri"https://neuroshapes.org/Trace")
      responseJson.as[PropertiesStatistics].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
