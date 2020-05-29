package ch.epfl.bluebrain.nexus.commons.es.client

import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.ScoredQueryResult
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import io.circe.{Decoder, Json}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ElasticSearchDecoderSpec extends AnyWordSpecLike with Matchers with Resources {

  "A ElasticSearchDecoder" should {
    implicit val D: Decoder[QueryResults[Json]] = ElasticSearchDecoder[Json]

    "decode ElasticSearch response " in {
      val response = jsonContentOf("/elastic_response.json")
      val json1    = Json.obj("key" -> Json.fromString("a"), "key2" -> Json.fromString("b"))
      val json2    = Json.obj("key" -> Json.fromString("c"), "key2" -> Json.fromString("d"))

      response.as[QueryResults[Json]].toOption.get shouldEqual ScoredQueryResults(
        2L,
        1f,
        List(ScoredQueryResult(0.5f, json1), ScoredQueryResult(0.8f, json2))
      )
    }

    "decode ElasticSearch empty response" in {
      val response = jsonContentOf("/elastic_response_0.json")
      response.as[QueryResults[Json]].toOption.get shouldEqual UnscoredQueryResults(0L, List.empty)
    }
  }
}
