package ch.epfl.bluebrain.nexus.commons.search

import cats.syntax.functor._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.ScoredQueryResult
import io.circe.generic.auto._
import io.circe.{Encoder, Json}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryResultSpec extends AnyWordSpecLike with Matchers {

  "A QueryResult Functor" should {
    implicit val queryResultEncoder: Encoder[ScoredQueryResult[Int]] =
      Encoder.encodeJson.contramap { qr =>
        Json.obj(
          "resultId" -> Json.fromString("/some/path"),
          "score"    -> Json.fromFloatOrString(qr.score),
          "source"   -> Json.fromInt(qr.source)
        )
      }
    "transform the source value" in {
      ScoredQueryResult(1f, 1).map(_ + 1) shouldEqual ScoredQueryResult(1f, 2)
    }
    "encodes a queryResult" in {
      import io.circe.syntax._
      val result = ScoredQueryResult(1f, 1): QueryResult[Int]
      result.asJson shouldEqual Json.obj(
        "resultId" -> Json.fromString("/some/path"),
        "score"    -> Json.fromFloatOrString(1f),
        "source"   -> Json.fromInt(result.source)
      )
    }
  }

}
