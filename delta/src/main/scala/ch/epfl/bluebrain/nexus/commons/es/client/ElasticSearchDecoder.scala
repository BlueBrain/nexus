package ch.epfl.bluebrain.nexus.commons.es.client

import cats.implicits._
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.commons.search.{QueryResult, QueryResults}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import io.circe.{Decoder, Json}

class ElasticSearchDecoder[A](implicit D: Decoder[A]) {

  private type ErrorOrResults = Either[Json, List[QueryResult[A]]]

  private def queryResults(json: Json, scored: Boolean): ErrorOrResults = {
    def queryResult(result: Json): Option[QueryResult[A]] = {
      result.hcursor.get[A]("_source") match {
        case Right(s) if scored => Some(ScoredQueryResult(result.hcursor.get[Float]("_score").getOrElse(0f), s))
        case Right(s)           => Some(UnscoredQueryResult(s))
        case _                  => None
      }
    }
    val hitsList = json.hcursor.downField("hits").downField("hits").focus.flatMap(_.asArray).getOrElse(Vector.empty)
    hitsList
      .foldM(List.empty[QueryResult[A]])((acc, json) => queryResult(json).map(_ :: acc).toRight(json))
      .map(_.reverse)
  }

  private def token(json: Json): Option[String] = {
    val hits   = json.hcursor.downField("hits").downField("hits")
    val length = hits.values.fold(1)(_.size)
    hits.downN(length - 1).downField("sort").focus.map(_.noSpaces)
  }

  private def decodeScoredQueryResults(maxScore: Float): Decoder[QueryResults[A]] =
    Decoder.decodeJson.emap { json =>
      queryResults(json, scored = true) match {
        case Right(list)   => Right(ScoredQueryResults(fetchTotal(json), maxScore, list, token(json)))
        // $COVERAGE-OFF$
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
        // $COVERAGE-ON$
      }
    }

  private val decodeUnscoredResults: Decoder[QueryResults[A]] =
    Decoder.decodeJson.emap { json =>
      queryResults(json, scored = false) match {
        case Right(list)   => Right(UnscoredQueryResults(fetchTotal(json), list, token(json)))
        // $COVERAGE-OFF$
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
        // $COVERAGE-ON$
      }
    }

  private def fetchTotal(json: Json): Long =
    json.hcursor.downField("hits").downField("total").get[Long]("value").getOrElse(0L)

  val decodeQueryResults: Decoder[QueryResults[A]] =
    Decoder.decodeJson.flatMap(
      _.hcursor.downField("hits").get[Float]("max_score").toOption.filterNot(f => f.isInfinite || f.isNaN) match {
        case Some(maxScore) => decodeScoredQueryResults(maxScore)
        case None           => decodeUnscoredResults
      }
    )
}

object ElasticSearchDecoder {

  /**
    * Construct a [Decoder] for [QueryResults] of the generic type ''A''
    *
    * @param D the implicitly available decoder for ''A''
    * @tparam A the generic type for the decoder
    */
  final def apply[A](implicit D: Decoder[A]): Decoder[QueryResults[A]] =
    new ElasticSearchDecoder[A].decodeQueryResults
}
