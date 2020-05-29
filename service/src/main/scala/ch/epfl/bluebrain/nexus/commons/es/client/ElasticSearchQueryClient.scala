package ch.epfl.bluebrain.nexus.commons.es.client

import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Query
import cats.effect.{Effect, Timer}
import cats.syntax.applicativeError._
import cats.syntax.functor._
import cats.syntax.show._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchBaseClient._
import ch.epfl.bluebrain.nexus.commons.es.client.ElasticSearchQueryClient._
import ch.epfl.bluebrain.nexus.commons.http.HttpClient.UntypedHttpClient
import ch.epfl.bluebrain.nexus.commons.http.{HttpClient, UnexpectedUnsuccessfulHttpResponse}
import ch.epfl.bluebrain.nexus.commons.search.{Pagination, QueryResults, Sort, SortList, _}
import ch.epfl.bluebrain.nexus.sourcing.RetryStrategyConfig
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import retry.CatsEffect._
import retry.syntax.all._

import scala.concurrent.ExecutionContext

/**
  * ElasticSearch query client implementation that uses a RESTful API endpoint for interacting with a ElasticSearch deployment.
  *
  * @param base the base uri of the ElasticSearch endpoint
  * @tparam F the monadic effect type
  */
private[client] class ElasticSearchQueryClient[F[_]: Timer](base: Uri)(
    implicit retryConfig: RetryStrategyConfig,
    cl: UntypedHttpClient[F],
    ec: ExecutionContext,
    F: Effect[F]
) extends ElasticSearchBaseClient[F] {

  private[client] val searchPath = "_search"

  private[client] val shards = Json.obj(
    "total"      -> Json.fromInt(0),
    "successful" -> Json.fromInt(0),
    "skipped"    -> Json.fromInt(0),
    "failed"     -> Json.fromInt(0)
  )

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param query        the initial search query
    * @param indices      the indices to use on search (if empty, searches in all the indices)
    * @param page         the pagination information
    * @param fields       the fields to be returned
    * @param sort         the sorting criteria
    * @param qp           the optional query parameters
    * @param isWorthRetry a function to decide if it is needed to retry
    * @tparam A the generic type to be returned
    */
  def apply[A](
      query: Json,
      indices: Set[String] = Set.empty,
      qp: Query = Query(ignoreUnavailable -> "true", allowNoIndices -> "true"),
      isWorthRetry: (Throwable => Boolean) = defaultWorthRetry
  )(page: Pagination, fields: Set[String] = Set.empty, sort: SortList = SortList.Empty, totalHits: Boolean = true)(
      implicit
      rs: HttpClient[F, QueryResults[A]]
  ): F[QueryResults[A]] =
    rs(
      Post(
        (base / indexPath(indices) / searchPath).withQuery(qp),
        query.addPage(page).addSources(fields).addSort(sort).addTotalHits(totalHits)
      )
    ).retryingOnSomeErrors(isWorthRetry)

  /**
    * Search ElasticSearch using provided query and return ES response with ''_shards'' information removed
    *
    * @param query        search query
    * @param indices      indices to search
    * @param qp           the optional query parameters
    * @param isWorthRetry a function to decide if it is needed to retry
    * @return ES response JSON
    */
  def searchRaw(
      query: Json,
      indices: Set[String] = Set.empty,
      qp: Query = Query(ignoreUnavailable -> "true", allowNoIndices -> "true"),
      isWorthRetry: (Throwable => Boolean) = defaultWorthRetry
  )(
      implicit
      rs: HttpClient[F, Json]
  ): F[Json] =
    rs(Post((base / indexPath(indices) / searchPath).withQuery(qp), query))
      .map { esResponse =>
        esResponse.mapObject(_.add("_shards", shards))
      }
      .recoverWith {
        case UnexpectedUnsuccessfulHttpResponse(r, body) =>
          F.raiseError(ElasticSearchFailure.fromStatusCode(r.status, body))
        case other => F.raiseError(other)
      }
      .retryingOnSomeErrors(isWorthRetry)
}
object ElasticSearchQueryClient {

  /**
    * Construct a [[ElasticSearchQueryClient]] from the provided ''base'' uri
    *
    * @param base        the base uri of the ElasticSearch endpoint
    * @tparam F the monadic effect type
    */
  final def apply[F[_]: Effect: Timer](base: Uri)(
      implicit retryConfig: RetryStrategyConfig,
      cl: UntypedHttpClient[F],
      ec: ExecutionContext
  ): ElasticSearchQueryClient[F] =
    new ElasticSearchQueryClient(base)

  private[client] implicit class JsonOpsSearch(query: Json) {

    private implicit val sortEncoder: Encoder[Sort] =
      Encoder.encodeJson.contramap(sort => Json.obj(s"${sort.value}" -> Json.fromString(sort.order.show)))

    /**
      * Adds pagination to the query
      *
      * @param page the pagination information
      */
    def addPage(page: Pagination): Json = page match {
      case FromPagination(from, size) =>
        query deepMerge Json.obj("from" -> Json.fromInt(from), "size" -> Json.fromInt(size))
      case SearchAfterPagination(searchAfter, size) =>
        query deepMerge Json.obj("search_after" -> searchAfter, "size" -> Json.fromInt(size))
    }

    def addTotalHits(value: Boolean): Json =
      query deepMerge Json.obj(trackTotalHits -> Json.fromBoolean(value))

    /**
      * Adds sources to the query, which defines what fields are going to be present in the response
      *
      * @param fields the fields we want to show in the response
      */
    def addSources(fields: Set[String]): Json =
      if (fields.isEmpty) query
      else query deepMerge Json.obj(source -> fields.asJson)

    /**
      * Adds sort to the query
      *
      * @param sortList the list of sorts
      */
    def addSort(sortList: SortList): Json =
      sortList match {
        case SortList.Empty  => query
        case SortList(sorts) => query deepMerge Json.obj("sort" -> sorts.asJson)
      }
  }
}
