package ch.epfl.bluebrain.nexus.kg.search

import java.time.Instant
import java.util.regex.Pattern.quote

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.commons.circe.syntax._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import ch.epfl.bluebrain.nexus.kg.search.QueryResultEncoder._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.util.{Randomness, Resources}
import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryResultEncoderSpec extends AnyWordSpecLike with Matchers with Resources with Randomness {

  implicit val orderedKeys = AppConfig.orderedKeys
  val org                  = genString()
  val proj                 = genString()
  val schema               = genString()
  val now                  = Instant.now()
  implicit val http        = HttpConfig("", 0, "v1", "http://nexus.com")
  implicit val uri         = Uri(s"http://nexus.com/resources/$org/$proj/$schema?type=someType&from=10&size=10")
  val before               = now.minusSeconds(60)

  "QueryResultsEncoder" should {
    def json(id: AbsoluteIri, createdAt: Instant): Json =
      jsonContentOf(
        "/resources/es-metadata.json",
        Map(
          quote("{id}")      -> id.asString,
          quote("{org}")     -> org,
          quote("{proj}")    -> proj,
          quote("{schema}")  -> schema,
          quote("{instant}") -> createdAt.toString
        )
      ) deepMerge Json.obj("_original_source" -> Json.fromString(Json.obj("k" -> Json.fromInt(1)).noSpaces))

    "encode ScoredQueryResults" in {
      val results: QueryResults[Json] = ScoredQueryResults[Json](
        3,
        0.3f,
        List(
          ScoredQueryResult(0.3f, json(url"http://nexus.com/result1", before)),
          ScoredQueryResult(0.2f, json(url"http://nexus.com/result2", before)),
          ScoredQueryResult(0.1f, json(url"http://nexus.com/result3", now))
        ),
        sort(now)
      )

      results.asJson.sortKeys shouldEqual jsonContentOf(
        "/search/scored-query-results.json",
        Map(
          quote("{org}")                -> org,
          quote("{proj}")               -> proj,
          quote("{schema}")             -> schema,
          quote("{before}")             -> before.toString,
          quote("{lastElementCreated}") -> now.toString,
          quote("{after}")              -> after(now)
        )
      )
    }
    "encode UnscoredQueryResults" in {
      val results: QueryResults[Json] = UnscoredQueryResults[Json](
        3,
        List(
          UnscoredQueryResult(json(url"http://nexus.com/result1", before)),
          UnscoredQueryResult(json(url"http://nexus.com/result2", before)),
          UnscoredQueryResult(json(url"http://nexus.com/result3", now))
        ),
        sort(now)
      )

      results.asJson.sortKeys shouldEqual jsonContentOf(
        "/search/unscored-query-results.json",
        Map(
          quote("{org}")                -> org,
          quote("{proj}")               -> proj,
          quote("{schema}")             -> schema,
          quote("{before}")             -> before.toString,
          quote("{lastElementCreated}") -> now.toString,
          quote("{after}")              -> after(now)
        )
      )

    }
  }

  private def sort(instant: Instant): Option[String] = Some(Json.arr(Json.fromString(instant.toString)).noSpaces)
  private def after(instant: Instant): String        =
    Query("after" -> List(Json.fromString(instant.toString)).asJson.noSpaces).toString()

}
