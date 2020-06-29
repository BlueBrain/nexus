package ch.epfl.bluebrain.nexus.kg.search

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import ch.epfl.bluebrain.nexus.commons.search.QueryResult.{ScoredQueryResult, UnscoredQueryResult}
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.{ScoredQueryResults, UnscoredQueryResults}
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, QueryResult, QueryResults}
import ch.epfl.bluebrain.nexus.kg.config.Contexts.{resourceCtxUri, searchCtxUri}
import ch.epfl.bluebrain.nexus.kg.directives.QueryDirectives.{after, from, size}
import ch.epfl.bluebrain.nexus.kg.indexing.SparqlLink
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.syntax._
import io.circe.{Encoder, Json}
trait LowPriorityQueryResultsEncoder {

  implicit def qrsEncoderLowPrio[A: Encoder]: Encoder[QueryResults[A]] =
    Encoder.instance(qrsEncoderJsonLinks[A](None).apply(_))

  implicit private val uriEncoder: Encoder[Uri] = Encoder.encodeString.contramap(_.toString)

  protected def qrsEncoderJsonLinks[A: Encoder](next: Option[Uri]): Encoder[QueryResults[A]] = {
    implicit def qrEncoderJson: Encoder[QueryResult[A]]     =
      Encoder.instance {
        case UnscoredQueryResult(v)      => v.asJson.removeKeys(nxv.original_source.prefix)
        case ScoredQueryResult(score, v) =>
          v.asJson.removeKeys(nxv.original_source.prefix) deepMerge
            Json.obj(nxv.score.prefix -> Json.fromFloatOrNull(score))
      }
    def json(total: Long, list: List[QueryResult[A]]): Json =
      Json
        .obj(nxv.total.prefix -> Json.fromLong(total), nxv.results.prefix -> Json.arr(list.map(qrEncoderJson(_)): _*))
        .addContext(searchCtxUri)
        .addContext(resourceCtxUri)

    Encoder.instance {
      case UnscoredQueryResults(total, list, _)         =>
        json(total, list) deepMerge Json.obj(nxv.next.prefix -> next.asJson)
      case ScoredQueryResults(total, maxScore, list, _) =>
        json(total, list) deepMerge
          Json.obj(nxv.maxScore.prefix -> maxScore.asJson, nxv.next.prefix -> next.asJson)
    }
  }
}

object QueryResultEncoder extends LowPriorityQueryResultsEncoder {

  implicit def qrsEncoderJson(implicit searchUri: Uri, http: HttpConfig): Encoder[QueryResults[Json]] =
    Encoder.instance { results =>
      val nextLink = results.token.flatMap(next(searchUri, _))
      qrsEncoderJsonLinks[Json](nextLink).apply(results)
    }

  implicit def qrsEncoderJson(implicit
      searchUri: Uri,
      pagination: FromPagination,
      http: HttpConfig
  ): Encoder[QueryResults[SparqlLink]] =
    Encoder.instance { results =>
      val nextLink = next(searchUri, results.total, pagination)
      qrsEncoderJsonLinks[SparqlLink](nextLink).apply(results)
    }

  private def next(current: Uri, total: Long, pagination: FromPagination)(implicit http: HttpConfig): Option[Uri] = {
    val nextFrom = pagination.from + pagination.size
    if (nextFrom < total.toInt) {
      val params = current.query().toMap + (from -> nextFrom.toString) + (size -> pagination.size.toString)
      Some(toPublic(current).withQuery(Query(params)))
    } else None
  }

  private def next(current: Uri, afterToken: String)(implicit http: HttpConfig): Option[Uri] =
    current.query().get(after) match {
      case Some(`afterToken`) => None
      case _                  =>
        val params = current.query().toMap + (after -> afterToken) - from
        Some(toPublic(current).withQuery(Query(params)))
    }

  private def toPublic(uri: Uri)(implicit http: HttpConfig): Uri =
    uri.copy(scheme = http.publicUri.scheme, authority = http.publicUri.authority)
}
