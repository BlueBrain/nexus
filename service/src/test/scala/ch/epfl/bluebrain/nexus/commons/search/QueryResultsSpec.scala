package ch.epfl.bluebrain.nexus.commons.search

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult._
import ch.epfl.bluebrain.nexus.commons.search.QueryResults._
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryResultsSpec extends AnyWordSpecLike with Matchers {

  "A QueryResults Functor" should {
    "transform the source and score values of the results" in {
      val qrs = ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, 1)))
      qrs.map(_ + 1) shouldEqual ScoredQueryResults(1L, 1f, List(ScoredQueryResult(1f, 2)))
    }

    "transform the score values of the results" in {
      val qrs = UnscoredQueryResults(1L, List(UnscoredQueryResult(1)))
      qrs.map(_ + 1) shouldEqual UnscoredQueryResults(1L, List(UnscoredQueryResult(2)))
    }

    "transform the generic queryResults values" in {
      val qrs = UnscoredQueryResults(1L, List(UnscoredQueryResult(1))): QueryResults[Int]
      qrs.map(_ + 1) shouldEqual UnscoredQueryResults(1L, List(UnscoredQueryResult(2)))
    }

    "encodes a queryResults" in {
      val result  = ScoredQueryResult(1f, 1): QueryResult[Int]
      val results = ScoredQueryResults(10L, 1f, List(result), Some("token")): QueryResults[Int]
      results.asJson shouldEqual Json.obj(
        "total"    -> Json.fromLong(results.total),
        "token"    -> Json.fromString("token"),
        "maxScore" -> Json.fromFloatOrNull(1f),
        "results"  -> Json.arr(result.asJson)
      )
    }

    "build from apply method" in {
      QueryResults(0L, List.empty[QueryResult[Int]]) shouldEqual UnscoredQueryResults(0L, List.empty[QueryResult[Int]])
      QueryResults(0L, 1f, List.empty[QueryResult[Int]]) shouldEqual ScoredQueryResults(
        0L,
        1f,
        List.empty[QueryResult[Int]]
      )
    }

    "change the underlying list type with copy method" in {
      val unscored = QueryResults(1L, List(UnscoredQueryResult(1)))
      unscored.copyWith(unscored.results.map(_.map(_.toString))) shouldEqual QueryResults(
        1L,
        List(UnscoredQueryResult("1"))
      )
      val scored = QueryResults(1L, List(ScoredQueryResult(1f, 1)))
      scored.copyWith(scored.results.map(_.map(_.toString))) shouldEqual QueryResults(
        1L,
        List(ScoredQueryResult(1f, "1"))
      )
    }
  }

}
