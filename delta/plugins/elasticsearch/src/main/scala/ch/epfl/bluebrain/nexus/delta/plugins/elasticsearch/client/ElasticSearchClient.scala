package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Created, NotFound, OK}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient._
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.circe.CirceMarshalling._
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClient.HttpResult
import ch.epfl.bluebrain.nexus.delta.sdk.http.HttpClientError.HttpClientStatusError
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.{ScoredResultEntry, UnscoredResultEntry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{ScoredSearchResults, UnscoredSearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, ResultEntry, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Name}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import com.typesafe.scalalogging.Logger
import io.circe.syntax._
import io.circe.{Decoder, Json, JsonObject}
import monix.bio.{IO, UIO}
import retry.syntax.all._

import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * A client that provides some of the functionality of the elasticsearch API.
  */
class ElasticSearchClient(client: HttpClient, endpoint: Uri)(implicit as: ActorSystem) {
  import as.dispatcher
  private val logger: Logger                                        = Logger[ElasticSearchClient]
  private val serviceName                                           = Name.unsafe("elasticsearch")
  private val scriptPath                                            = "_scripts"
  private val docPath                                               = "_doc"
  private val allIndexPath                                          = "_all"
  private val bulkPath                                              = "_bulk"
  private val tasksPath                                             = "_tasks"
  private val waitForCompletion                                     = "wait_for_completion"
  private val ignoreUnavailable                                     = "ignore_unavailable"
  private val allowNoIndices                                        = "allow_no_indices"
  private val updateByQueryPath                                     = "_update_by_query"
  private val searchPath                                            = "_search"
  private val newLine                                               = System.lineSeparator()
  private val `application/x-ndjson`                                = MediaType.applicationWithFixedCharset("x-ndjson", HttpCharsets.`UTF-8`, "json")
  private val defaultQuery                                          = Map(ignoreUnavailable -> "true", allowNoIndices -> "true")
  private val defaultUpdateByQuery                                  = defaultQuery + (waitForCompletion -> "false")
  private val updateByQueryStrategy: RetryStrategy[HttpClientError] =
    RetryStrategy.constant(
      1.second,
      Int.MaxValue,
      {
        case err: HttpClientStatusError if err.code == StatusCodes.NotFound => false
        case _                                                              => true
      },
      logError(logger, "updateByQuery")
    )

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
    * Attempts to create an index recovering gracefully when the index already exists.
    *
    * @param index        the index
    * @param mappings     the optional mappings section of the index payload
    * @param settings     the optional settings section of the index payload
    * @return ''true'' when the index has been created and ''false'' when it already existed, wrapped in an IO
    */
  def createIndex(index: IndexLabel, mappings: Option[JsonObject], settings: Option[JsonObject]): HttpResult[Boolean] =
    createIndex(index, JsonObject.empty.addIfExists("mappings", mappings).addIfExists("settings", settings))

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
  def bulk(ops: Seq[ElasticSearchBulk], qp: Query = Query.Empty): HttpResult[Unit] =
    if (ops.isEmpty) IO.unit
    else {
      val entity = HttpEntity(`application/x-ndjson`, ops.map(_.payload).mkString("", newLine, newLine))
      val req    = Post((endpoint / bulkPath).withQuery(qp), entity)
      client.toJson(req).flatMap { json =>
        IO.unless(json.hcursor.get[Boolean]("errors").contains(false))(
          IO.raiseError(HttpClientStatusError(req, BadRequest, json.noSpaces))
        )
      }
    }

  /**
    * Creates a bulk update with the operations defined on the provided ''ops'' argument. This bulk will wait for the
    * shards involved to refresh their indices so that the newly created documents are accessible for search
    *
    * @param ops          the list of operations to be included in the bulk update
    */
  def bulkWaitFor(ops: Seq[ElasticSearchBulk]): HttpResult[Unit] =
    bulk(ops, Query("refresh" -> "wait_for"))

  /**
    * Creates a script on Elasticsearch with the passed ''id'' and ''content''
    */
  def createScript(id: String, content: String): HttpResult[Unit] = {
    val payload = Json.obj("script" -> Json.obj("lang" -> "painless".asJson, "source" -> content.asJson))
    val req     = Put(endpoint / scriptPath / UrlUtils.encode(id), payload)
    client.toJson(req).flatMap { json =>
      IO.unless(json.hcursor.get[Boolean]("acknowledged").contains(true))(
        IO.raiseError(HttpClientStatusError(req, BadRequest, json.noSpaces))
      )
    }
  }

  /**
    * Runs an update by query with the passed ''query'' and ''indices''. The query is run as a task
    * and the task is requested until it finished
    *
    * @param query   the search query
    * @param indices the indices to use on search (if empty, searches in all the indices)
    */
  def updateByQuery(query: JsonObject, indices: Set[String]): HttpResult[Unit] = {
    val updateEndpoint = (endpoint / indexPath(indices) / updateByQueryPath).withQuery(Uri.Query(defaultUpdateByQuery))
    val req            = Post(updateEndpoint, query)
    for {
      json   <- client.toJson(req)
      taskId <- IO.fromEither(
                  json.hcursor.get[String]("task").leftMap(_ => HttpClientStatusError(req, BadRequest, json.noSpaces))
                )
      _      <- client
                  .toJson(Get((endpoint / tasksPath / taskId).withQuery(Query(waitForCompletion -> "true"))))
                  .retryingOnSomeErrors(
                    updateByQueryStrategy.retryWhen,
                    updateByQueryStrategy.policy,
                    updateByQueryStrategy.onError
                  )
    } yield ()
  }

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param params  the filter parameters
    * @param indices the indices to use on search (if empty, searches in all the indices)
    * @param qp      the query parameters
    * @param page    the pagination information
    * @param sort    the sorting criteria
    */
  def search(
      params: ResourcesSearchParams,
      indices: Set[String],
      qp: Query
  )(
      page: Pagination,
      sort: SortList
  )(implicit base: BaseUri): HttpResult[SearchResults[JsonObject]] =
    search(
      QueryBuilder(params).withPage(page).withTotalHits(true).withSort(sort),
      indices,
      qp
    )

  /**
    * Search for the provided ''query'' inside the ''indices'' returning a parsed result as a [[SearchResults]].
    *
    * @param query        the search query
    * @param indices      the indices to use on search (if empty, searches in all the indices)
    * @param qp           the optional query parameters
    */
  def search(
      query: QueryBuilder,
      indices: Set[String],
      qp: Query
  ): HttpResult[SearchResults[JsonObject]] =
    searchAs[SearchResults[JsonObject]](query, indices, qp)

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param query   the initial search query
    * @param indices the indices to use on search (if empty, searches in all the indices)
    * @param qp      the optional query parameters
    * @param sort    the sorting criteria
    */
  def search(
      query: JsonObject,
      indices: Set[String],
      qp: Query
  )(
      sort: SortList = SortList.empty
  ): HttpResult[Json] = {
    val searchEndpoint = (endpoint / indexPath(indices) / searchPath).withQuery(Uri.Query(defaultQuery ++ qp.toMap))
    val payload        = QueryBuilder(query).withSort(sort).withTotalHits(true).build
    client.toJson(Post(searchEndpoint, payload))
  }

  /**
    * Search for the provided ''query'' inside the ''indices'' returning a parsed result as [[T]].
    *
    * @param query        the search query
    * @param indices      the indices to use on search (if empty, searches in all the indices)
    * @param qp           the optional query parameters
    */
  def searchAs[T: Decoder: ClassTag](
      query: QueryBuilder,
      indices: Set[String],
      qp: Query
  ): HttpResult[T] = {
    val searchEndpoint = (endpoint / indexPath(indices) / searchPath).withQuery(Uri.Query(defaultQuery ++ qp.toMap))
    client.fromJsonTo[T](Post(searchEndpoint, query.build))
  }

  private def discardEntity(resp: HttpResponse) =
    UIO.delay(resp.discardEntityBytes()) >> IO.unit

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

  final private[client] case class Count(value: Long)
  private[client] object Count {
    implicit val decodeCount: Decoder[Count] =
      Decoder.instance(_.get[Long]("count").map(Count(_)))
  }
}
