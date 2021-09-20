package ch.epfl.bluebrain.nexus.delta.plugins.statistics

import ch.epfl.bluebrain.nexus.delta.plugins.statistics.model.StatisticsGraph
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.testkit.{EitherValuable, IOValues, TestHelpers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class StatisticsGraphSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelpers
    with EitherValuable
    with IOValues
    with ContextFixtures {

  "A StatisticsGraph" should {
    val responseJson = jsonContentOf("paths-relationships-aggregations-response.json")
    val expected     = jsonContentOf("statistics-graph.json")

    "be converted from Elasticsearch response to client response" in {
      responseJson.as[StatisticsGraph].rightValue.toCompactedJsonLd.accepted.json shouldEqual expected
    }
  }

}
