package ai.senscience.nexus.delta.plugins.graph.analytics

import ai.senscience.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, TitaniumJsonLdApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class AnalyticsGraphSpec extends CatsEffectSpec with ContextFixtures {

  "A AnalyticsGraph" should {

    implicit val jsonLdApi: JsonLdApi = TitaniumJsonLdApi.lenient

    val responseJson = jsonContentOf("paths-relationships-aggregations-response.json")
    val expected     = jsonContentOf("analytics-graph.json")

    "be converted from Elasticsearch response to client response" in {
      responseJson.as[AnalyticsGraph].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
