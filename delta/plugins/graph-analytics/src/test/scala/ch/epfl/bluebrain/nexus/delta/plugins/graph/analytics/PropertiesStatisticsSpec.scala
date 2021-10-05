package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class PropertiesStatisticsSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with IOValues
    with ContextFixtures {

  "PropertiesStatistics" should {

    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient

    val responseJson = jsonContentOf("paths-properties-aggregations-response.json")
    val expected     = jsonContentOf("properties-tree.json")

    "be converted from Elasticsearch response to client response" in {
      implicit val d = propertiesDecoderFromEsAggregations(iri"https://neuroshapes.org/Trace")
      responseJson.as[PropertiesStatistics].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
