package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.AnalyticsGraph
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.api.{JsonLdApi, JsonLdJavaApi}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AnalyticsGraphSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with IOValues
    with ContextFixtures {

  "A AnalyticsGraph" should {

    implicit val jsonLdApi: JsonLdApi = JsonLdJavaApi.lenient

    val responseJson = jsonContentOf("paths-relationships-aggregations-response.json")
    val expected     = jsonContentOf("analytics-graph.json")

    "be converted from Elasticsearch response to client response" in {
      responseJson.as[AnalyticsGraph].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
