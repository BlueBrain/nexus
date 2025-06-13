package ai.senscience.nexus.delta.plugins.graph.analytics

import ai.senscience.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics
import ai.senscience.nexus.delta.plugins.graph.analytics.model.PropertiesStatistics.propertiesDecoderFromEsAggregations
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class PropertiesStatisticsSpec extends CatsEffectSpec with ContextFixtures {

  "PropertiesStatistics" should {

    implicit val jsonLdApi: JsonLdApi = TitaniumJsonLdApi.lenient

    val responseJson = jsonContentOf("paths-properties-aggregations-response.json")
    val expected     = jsonContentOf("properties-tree.json")

    "be converted from Elasticsearch response to client response" in {
      implicit val d = propertiesDecoderFromEsAggregations(iri"https://neuroshapes.org/Trace")
      responseJson.as[PropertiesStatistics].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
