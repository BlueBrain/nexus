package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Created, NotFound, OK}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient._
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.Name
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.{ScoredResultEntry, UnscoredResultEntry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{ScoredSearchResults, UnscoredSearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, ResultEntry, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe.{Decoder, Json, JsonObject}
import monix.bio.{IO, UIO}
import io.circe.syntax._

import scala.concurrent.duration._

/**
  * A client that provides some of the functionality of the elasticsearch API.
  */
class ElasticSearchClient(client: HttpClient, endpoint: Uri)(implicit as: ActorSystem) {
  import as.dispatcher
  private val serviceName                                        = Name.unsafe("elasticsearch")
  private val docPath                                            = "_doc"
  private val allIndexPath                                       = "_all"
  private val bulkPath                                           = "_bulk"
  private val ignoreUnavailable                                  = "ignore_unavailable"
  private val allowNoIndices                                     = "allow_no_indices"
  private val searchPath                                         = "_search"
  private val newLine                                            = System.lineSeparator()
  private val `application/x-ndjson`: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("x-ndjson", HttpCharsets.`UTF-8`, "json")

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: UIO[ServiceDescription] =
    client
      .fromJsonTo[ResolvedServiceDescription](Get(endpoint))
      .timeout(3.seconds)
      .redeem(
        _ => ServiceDescription.unresolved(serviceName),
        _.map(_.copy(name = serviceName)).getOrElse(ServiceDescription.unresolved(serviceName))
      )

  /**
    * Verifies if an index exists, recovering gracefully when the index does not exists.
    *
    * @param index        the index to verify
    * @return ''true'' when the index exists and ''false'' when it doesn't, wrapped in an IO
    */
  def existsIndex(index: IndexLabel): HttpResult[Boolean] =
    client(Head(endpoint / index.value)) {
      case resp if resp.status == OK       => IO.pure(true)
      case resp if resp.status == NotFound => IO.pure(false)
    }

  /**
    * Attempts to create an index recovering gracefully when the index already exists.
    *
    * @param index        the index
    * @param payload      the payload to attach to the index when it does not exist
    * @return ''true'' when the index has been created and ''false'' when it already existed, wrapped in an IO
    */
  def createIndex(index: IndexLabel, payload: JsonObject = JsonObject.empty): HttpResult[Boolean] = {
    existsIndex(index).flatMap {
      case false =>
        client(Put(endpoint / index.value, payload)) {
          case resp if resp.status.isSuccess() => discardEntity(resp) >> IO.pure(true)
        }
      case true  =>
        IO.pure(false)
    }
  }

  /**
    * Attempts to delete an index recovering gracefully when the index is not found.
    *
    * @param index        the index
    * @return ''true'' when the index has been deleted and ''false'' when it didn't exist, wrapped in an IO
    */
  def deleteIndex(index: IndexLabel): HttpResult[Boolean] =
    client(Delete(endpoint / index.value)) {
      case resp if resp.status == OK       => discardEntity(resp) >> IO.pure(true)
      case resp if resp.status == NotFound => discardEntity(resp) >> IO.pure(false)
    }

  /**
    * Creates or replaces a new document inside the ''index'' with the provided ''payload''
    *
    * @param index   the index to use
    * @param id      the id of the document to update
    * @param payload the document's payload
    */
  def replace(
      index: IndexLabel,
      id: String,
      payload: JsonObject
  ): HttpResult[Unit] =
    client(Put(endpoint / index.value / docPath / UrlUtils.encode(id), payload)) {
      case resp if resp.status == Created || resp.status == OK => discardEntity(resp)
    }

  /**
    * Deletes the document with the provided ''id''
    *
    * @param index        the index to use
    * @param id           the id to delete
    * @return ''true'' when the document has been deleted and ''false'' when it didn't exist, wrapped in an IO
    */
  def delete(index: IndexLabel, id: String): HttpResult[Boolean] =
    client(Delete(endpoint / index.value / docPath / UrlUtils.encode(id))) {
      case resp if resp.status == OK       => discardEntity(resp) >> IO.pure(true)
      case resp if resp.status == NotFound => discardEntity(resp) >> IO.pure(false)
    }

  /**
    * Creates a bulk update with the operations defined on the provided ''ops'' argument.
    *
    * @param ops          the list of operations to be included in the bulk update
    */
  def bulk(ops: Seq[ElasticSearchBulk]): HttpResult[Unit] =
    if (ops.isEmpty) IO.unit
    else {
      val entity = HttpEntity(`application/x-ndjson`, ops.map(_.payload).mkString("", newLine, newLine))
      val req    = Post(endpoint / bulkPath, entity)
      client
        .toJson(Post(endpoint / bulkPath, entity))
        .flatMap { json =>
          IO.unless(json.hcursor.get[Boolean]("errors").contains(false))(
            IO.raiseError(HttpClientStatusError(req, BadRequest, json.noSpaces))
          )
        }
    }

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param query        the initial search query
    * @param indices      the indices to use on search (if empty, searches in all the indices)
    * @param page         the pagination information
    * @param fields       the fields to be returned
    * @param sort         the sorting criteria
    * @param qp           the optional query parameters
    */
  def search(
      query: JsonObject,
      indices: Set[String] = Set.empty,
      qp: Query = Query(ignoreUnavailable -> "true", allowNoIndices -> "true")
  )(
      page: Pagination,
      totalHits: Boolean = true,
      fields: Set[String] = Set.empty,
      sort: SortList = SortList.empty
  ): HttpResult[SearchResults[JsonObject]] = {
    val searchEndpoint = (endpoint / indexPath(indices) / searchPath).withQuery(qp)
    val payload        = QueryBuilder(query).withPage(page).withFields(fields).withSort(sort).withTotalHits(totalHits).build
    client.fromJsonTo[SearchResults[JsonObject]](Post(searchEndpoint, payload))
  }

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param query        the initial search query
    * @param indices      the indices to use on search (if empty, searches in all the indices)
    * @param page         the pagination information
    * @param fields       the fields to be returned
    * @param sort         the sorting criteria
    * @param qp           the optional query parameters
    */
  def searchRaw(
      query: JsonObject,
      indices: Set[String] = Set.empty,
      qp: Query = Query(ignoreUnavailable -> "true", allowNoIndices -> "true")
  )(
      page: Pagination,
      totalHits: Boolean = true,
      fields: Set[String] = Set.empty,
      sort: SortList = SortList.empty
  ): HttpResult[Json] = {
    val searchEndpoint = (endpoint / indexPath(indices) / searchPath).withQuery(qp)
    val payload        = QueryBuilder(query).withPage(page).withFields(fields).withSort(sort).withTotalHits(totalHits).build
    client.toJson(Post(searchEndpoint, payload)).onErrorRecoverWith { err =>
      err.jsonBody.map(IO.pure).getOrElse(IO.raiseError(err))
    }
  }

  private def discardEntity(resp: HttpResponse) =
    IO.delay(resp.discardEntityBytes()).hideErrors >> IO.unit

  private def indexPath(indices: Set[String]): String =
    if (indices.isEmpty) allIndexPath else indices.mkString(",")

  implicit private val resolvedServiceDescriptionDecoder: Decoder[ResolvedServiceDescription] =
    Decoder.instance { hc =>
      hc.downField("version").get[String]("number").map(ServiceDescription(serviceName, _))
    }

}

object ElasticSearchClient {

  private def queryResults(json: JsonObject, scored: Boolean): Either[JsonObject, Vector[ResultEntry[JsonObject]]] = {

    def inner(result: JsonObject): Option[ResultEntry[JsonObject]] =
      result("_source").flatMap(_.asObject).map {
        case source if scored => ScoredResultEntry(result("_score").flatMap(_.as[Float].toOption).getOrElse(0), source)
        case source           => UnscoredResultEntry(source)
      }

    val hitsList = json.asJson.hcursor.downField("hits").getOrElse("hits")(Vector.empty[JsonObject]).leftMap(_ => json)
    hitsList.flatMap { vector =>
      vector.foldM(Vector.empty[ResultEntry[JsonObject]])((acc, json) => inner(json).map(acc :+ _).toRight(json))
    }
  }

  private def token(json: JsonObject): Option[String] = {
    val hits   = json.asJson.hcursor.downField("hits").downField("hits")
    val length = hits.values.fold(1)(_.size)
    hits.downN(length - 1).downField("sort").focus.map(_.noSpaces)
  }

  private def decodeScoredResults(maxScore: Float): Decoder[SearchResults[JsonObject]] =
    Decoder.decodeJsonObject.emap { json =>
      queryResults(json, scored = true) match {
        case Right(list)   => Right(ScoredSearchResults(fetchTotal(json), maxScore, list, token(json)))
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
      }
    }

  private val decodeUnscoredResults: Decoder[SearchResults[JsonObject]] =
    Decoder.decodeJsonObject.emap { json =>
      queryResults(json, scored = false) match {
        case Right(list)   => Right(UnscoredSearchResults(fetchTotal(json), list, token(json)))
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
      }
    }

  private def fetchTotal(json: JsonObject): Long =
    json.asJson.hcursor.downField("hits").downField("total").get[Long]("value").getOrElse(0L)

  implicit val decodeQueryResults: Decoder[SearchResults[JsonObject]] =
    Decoder.decodeJsonObject.flatMap(
      _.asJson.hcursor
        .downField("hits")
        .get[Float]("max_score")
        .toOption
        .filterNot(f => f.isInfinite || f.isNaN) match {
        case Some(maxScore) => decodeScoredResults(maxScore)
        case None           => decodeUnscoredResults
      }
    )
}
