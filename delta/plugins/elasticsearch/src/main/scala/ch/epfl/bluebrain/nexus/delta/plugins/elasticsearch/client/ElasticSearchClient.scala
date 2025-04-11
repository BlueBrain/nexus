package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding._
import akka.http.scaladsl.model.StatusCodes.{BadRequest, Created, NotFound, OK}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.BasicHttpCredentials
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.RetryStrategy.logError
import ch.epfl.bluebrain.nexus.delta.kernel.circe.CirceMarshalling._
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError.{HttpClientStatusError, HttpUnexpectedError}
import ch.epfl.bluebrain.nexus.delta.kernel.http.{HttpClient, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.kernel.{Logger, RetryStrategy}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.BulkResponse.MixedOutcomes.Outcome
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.{ScoredResultEntry, UnscoredResultEntry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{ScoredSearchResults, UnscoredSearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{ResultEntry, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import io.circe._
import io.circe.literal._
import io.circe.syntax._

import scala.concurrent.duration._
import scala.reflect.ClassTag

/**
  * A client that provides some of the functionality of the elasticsearch API.
  */
class ElasticSearchClient(client: HttpClient, endpoint: Uri, maxIndexPathLength: Int)(implicit
    credentials: Option[BasicHttpCredentials],
    as: ActorSystem
) {
  import as.dispatcher
  private val logger                                                = Logger[ElasticSearchClient]
  private val serviceName                                           = "elasticsearch"
  private val scriptPath                                            = "_scripts"
  private val docPath                                               = "_doc"
  private val allIndexPath                                          = "_all"
  private val aliasPath                                             = "_aliases"
  private val bulkPath                                              = "_bulk"
  private val refreshPath                                           = "_refresh"
  private val indexTemplate                                         = "_index_template"
  private val mget                                                  = "_mget"
  private val tasksPath                                             = "_tasks"
  private val waitForCompletion                                     = "wait_for_completion"
  private val refreshParam                                          = "refresh"
  private val ignoreUnavailable                                     = "ignore_unavailable"
  private val allowNoIndices                                        = "allow_no_indices"
  private val deleteByQueryPath                                     = "_delete_by_query"
  private val updateByQueryPath                                     = "_update_by_query"
  private val countPath                                             = "_count"
  private val searchPath                                            = "_search"
  private val source                                                = "_source"
  private val mapping                                               = "_mapping"
  private val pit                                                   = "_pit"
  private val newLine                                               = System.lineSeparator()
  private val `application/x-ndjson`: MediaType.WithFixedCharset    =
    MediaType.applicationWithFixedCharset("x-ndjson", HttpCharsets.`UTF-8`, "json")
  private val defaultQuery                                          = Map(ignoreUnavailable -> "true", allowNoIndices -> "true")
  private val defaultUpdateByQuery                                  = defaultQuery + (waitForCompletion -> "false")
  private val defaultDeleteByQuery                                  = defaultQuery + (waitForCompletion -> "true")
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
  def serviceDescription: IO[ServiceDescription] =
    client
      .fromJsonTo[ResolvedServiceDescription](Get(endpoint).withHttpCredentials)
      .timeout(1.second)
      .recover(_ => ServiceDescription.unresolved(serviceName))

  /**
    * Verifies if an index exists, recovering gracefully when the index does not exists.
    *
    * @param index
    *   the index to verify
    * @return
    *   ''true'' when the index exists and ''false'' when it doesn't, wrapped in an IO
    */
  def existsIndex(index: IndexLabel): IO[Boolean] =
    client.run(Head(endpoint / index.value).withHttpCredentials) {
      case resp if resp.status == OK       => IO.pure(true)
      case resp if resp.status == NotFound => IO.pure(false)
    }

  /**
    * Attempts to create an index recovering gracefully when the index already exists.
    *
    * @param index
    *   the index
    * @param payload
    *   the payload to attach to the index when it does not exist
    * @return
    *   ''true'' when the index has been created and ''false'' when it already existed, wrapped in an IO
    */
  def createIndex(index: IndexLabel, payload: JsonObject = JsonObject.empty): IO[Boolean] = {
    existsIndex(index).flatMap {
      case false =>
        client.run(Put(endpoint / index.value, payload).withHttpCredentials) {
          case resp if resp.status.isSuccess() => discardEntity(resp).as(true)
        }
      case true  =>
        IO.pure(false)
    }
  }

  /**
    * Attempts to create an index recovering gracefully when the index already exists.
    *
    * @param index
    *   the index
    * @param mappings
    *   the optional mappings section of the index payload
    * @param settings
    *   the optional settings section of the index payload
    * @return
    *   ''true'' when the index has been created and ''false'' when it already existed, wrapped in an IO
    */
  def createIndex(index: IndexLabel, mappings: Option[JsonObject], settings: Option[JsonObject]): IO[Boolean] =
    createIndex(index, JsonObject.empty.addIfExists("mappings", mappings).addIfExists("settings", settings))

  /**
    * Attempts to create an index template
    *
    * @param name
    *   the template name
    * @param template
    *   the template payload
    * @return
    *   ''true'' when the index template has been created
    */
  def createIndexTemplate(name: String, template: JsonObject): IO[Boolean] =
    client.run(Put(endpoint / indexTemplate / name, template).withHttpCredentials) {
      case resp if resp.status.isSuccess() => discardEntity(resp).as(true)
    }

  /**
    * Attempts to delete an index recovering gracefully when the index is not found.
    *
    * @param index
    *   the index
    * @return
    *   ''true'' when the index has been deleted and ''false'' when it didn't exist, wrapped in an IO
    */
  def deleteIndex(index: IndexLabel): IO[Boolean] =
    client.run(Delete(endpoint / index.value).withHttpCredentials) {
      case resp if resp.status == OK       => discardEntity(resp).as(true)
      case resp if resp.status == NotFound => discardEntity(resp).as(false)
    }

  /**
    * Creates or replaces a new document inside the ''index'' with the provided ''payload''
    *
    * @param index
    *   the index to use
    * @param id
    *   the id of the document to update
    * @param payload
    *   the document's payload
    */
  def replace(
      index: IndexLabel,
      id: String,
      payload: JsonObject
  ): IO[Unit] =
    client.run(Put(endpoint / index.value / docPath / UrlUtils.encode(id), payload).withHttpCredentials) {
      case resp if resp.status == Created || resp.status == OK => discardEntity(resp)
    }

  /**
    * Deletes the document with the provided ''id''
    *
    * @param index
    *   the index to use
    * @param id
    *   the id to delete
    * @return
    *   ''true'' when the document has been deleted and ''false'' when it didn't exist, wrapped in an IO
    */
  def delete(index: IndexLabel, id: String): IO[Boolean] =
    client.run(Delete(endpoint / index.value / docPath / UrlUtils.encode(id)).withHttpCredentials) {
      case resp if resp.status == OK       => discardEntity(resp).as(true)
      case resp if resp.status == NotFound => discardEntity(resp).as(false)
    }

  /**
    * Creates a bulk update with the operations defined on the provided ''ops'' argument.
    *
    * @param ops
    *   the list of operations to be included in the bulk update
    * @param refresh
    *   the value for the `refresh` Elasticsearch parameter
    */
  def bulk(ops: Seq[ElasticSearchAction], refresh: Refresh = Refresh.False): IO[BulkResponse] = {
    if (ops.isEmpty) IO.pure(BulkResponse.Success)
    else {
      val bulkEndpoint = (endpoint / bulkPath).withQuery(Query(refreshParam -> refresh.value))
      val entity       = HttpEntity(`application/x-ndjson`, ops.map(_.payload).mkString("", newLine, newLine))
      val req          = Post(bulkEndpoint, entity).withHttpCredentials

      client
        .toJson(req)
        .attemptNarrow[HttpClientError]
        .flatMap {
          case Left(e)     => IO.fromEither(BulkResponse(e)).orRaise(e)
          case Right(json) =>
            IO.fromEither(BulkResponse(json)).adaptError { e => HttpUnexpectedError(req, e.getMessage) }
        }
    }
  }

  /**
    * Creates a script on Elasticsearch with the passed ''id'' and ''content''
    */
  def createScript(id: String, content: String): IO[Unit] = {
    val payload = Json.obj("script" -> Json.obj("lang" -> "painless".asJson, "source" -> content.asJson))
    val req     = Put(endpoint / scriptPath / UrlUtils.encode(id), payload).withHttpCredentials
    client.toJson(req).flatMap { json =>
      IO.raiseWhen(!json.hcursor.get[Boolean]("acknowledged").contains(true))(
        HttpClientStatusError(req, BadRequest, json.noSpaces)
      )
    }
  }

  /**
    * Runs an update by query with the passed ''query'' and ''indices''. The query is run as a task and the task is
    * requested until it finished
    *
    * @param query
    *   the search query
    * @param indices
    *   the indices targeted by the update query
    */
  def updateByQuery(query: JsonObject, indices: Set[String]): IO[Unit] = {
    val (indexPath, q) = indexPathAndQuery(indices, QueryBuilder(query))
    val updateEndpoint = (endpoint / indexPath / updateByQueryPath).withQuery(Uri.Query(defaultUpdateByQuery))
    val req            = Post(updateEndpoint, q.build).withHttpCredentials
    for {
      json   <- client.toJson(req)
      taskId <-
        IO.fromEither(json.hcursor.get[String]("task")).orRaise(HttpClientStatusError(req, BadRequest, json.noSpaces))
      taskReq = Get((endpoint / tasksPath / taskId).withQuery(Query(waitForCompletion -> "true"))).withHttpCredentials
      _      <- client.toJson(taskReq).retry(updateByQueryStrategy)
    } yield ()
  }

  /**
    * Runs an delete by query with the passed ''query'' and ''index''. The query is run as a task and the task is
    * requested until it finished
    *
    * @param query
    *   the search query
    * @param index
    *   the index targeted by the delete query
    */
  def deleteByQuery(query: JsonObject, index: IndexLabel): IO[Unit] = {
    val deleteEndpoint = (endpoint / index.value / deleteByQueryPath).withQuery(Uri.Query(defaultDeleteByQuery))
    val req            = Post(deleteEndpoint, query).withHttpCredentials
    client.toJson(req).void
  }

  /**
    * Get the source of the Elasticsearch document
    * @param index
    *   the index to look in
    * @param id
    *   the identifier of the document
    */
  def getSource[R: Decoder: ClassTag](index: IndexLabel, id: String): IO[R] = {
    val sourceEndpoint = endpoint / index.value / source / id
    val req            = Get(sourceEndpoint).withHttpCredentials
    client.fromJsonTo[R](req)
  }

  /**
    * Perform a multi-get from the given index and attempts to extract the provided field from the document
    * @param index
    *   the index to use
    * @param ids
    *   the ids to
    * @param field
    *   the field to extract
    */
  def multiGet[R: Decoder](index: IndexLabel, ids: Set[String], field: String): IO[Map[String, Option[R]]] = {
    if (ids.isEmpty) IO.pure(Map.empty)
    else {
      val multiGetEndpoint = (endpoint / index.value / mget).withQuery(Uri.Query(Map("_source" -> field)))
      val req              = Get(multiGetEndpoint, Json.obj("ids" -> ids.asJson)).withHttpCredentials

      client
        .toJson(req)
        .flatMap { json =>
          IO.fromOption(json.hcursor.downField("docs").focus.flatMap(_.asArray).map {
            _.mapFilter { r =>
              if (r.hcursor.get[Boolean]("found").contains(true))
                r.hcursor
                  .get[String]("_id")
                  .map { id =>
                    id -> r.hcursor.downField("_source").get[R](field).toOption
                  }
                  .toOption
              else None
            }.toMap
          })(HttpClientStatusError(req, BadRequest, json.noSpaces))
        }
    }
  }

  /**
    * Returns the number of document in a given index
    * @param index
    *   the index to use
    */
  def count(index: String): IO[Long] = {
    val req = Get(endpoint / index / countPath).withHttpCredentials
    client.toJson(req).flatMap { json =>
      val count = json.hcursor.downField("count").focus.flatMap(_.asNumber.flatMap(_.toLong))
      IO.fromOption(count)(
        HttpClientStatusError(req, BadRequest, json.noSpaces)
      )
    }
  }

  /**
    * Search for the provided ''query'' inside the ''index'' returning a parsed result as a [[SearchResults]].
    *
    * @param query
    *   the search query
    * @param indices
    *   the indices to use on search (if empty, searches in all the indices)
    * @param qp
    *   the optional query parameters
    */
  def search(
      query: QueryBuilder,
      indices: Set[String],
      qp: Query
  ): IO[SearchResults[JsonObject]] =
    searchAs[SearchResults[JsonObject]](query, indices, qp)(SearchResults.empty)

  /**
    * Search for the provided ''query'' inside the ''indices''
    *
    * @param query
    *   the initial search query
    * @param indices
    *   the indices to use on search (if empty, searches in all the indices)
    * @param qp
    *   the optional query parameters
    * @param sort
    *   the sorting criteria
    */
  def search(
      query: JsonObject,
      indices: Set[String],
      qp: Query
  )(
      sort: SortList = SortList.empty
  ): IO[Json] =
    if (indices.isEmpty) IO.pure(emptyResults)
    else {
      val (indexPath, q) = indexPathAndQuery(indices, QueryBuilder(query))
      val searchEndpoint = (endpoint / indexPath / searchPath).withQuery(Uri.Query(defaultQuery ++ qp.toMap))
      val payload        = q.withSort(sort).withTotalHits(true).build
      client.toJson(Post(searchEndpoint, payload).withHttpCredentials)
    }

  /**
    * Search for the provided ''query'' inside the ''indices'' returning a parsed result as [[T]].
    *
    * @param query
    *   the search query
    * @param indices
    *   the indices to use on search
    * @param qp
    *   the optional query parameters
    */
  def searchAs[T: Decoder: ClassTag](
      query: QueryBuilder,
      indices: Set[String],
      qp: Query
  )(onEmpty: => T): IO[T] =
    if (indices.isEmpty)
      IO.pure(onEmpty)
    else {
      val (indexPath, q) = indexPathAndQuery(indices, query)
      val searchEndpoint = (endpoint / indexPath / searchPath).withQuery(Uri.Query(defaultQuery ++ qp.toMap))
      client.fromJsonTo[T](Post(searchEndpoint, q.build).withHttpCredentials)
    }

  /**
    * Search for the provided ''query'' inside the ''indices'' returning a parsed result as [[T]].
    *
    * @param query
    *   the search query
    * @param index
    *   the index to use on search
    * @param qp
    *   the optional query parameters
    */
  def searchAs[T: Decoder: ClassTag](
      query: QueryBuilder,
      index: String,
      qp: Query
  ): IO[T] = {
    val searchEndpoint = (endpoint / index / searchPath).withQuery(Uri.Query(defaultQuery ++ qp.toMap))
    client.fromJsonTo[T](Post(searchEndpoint, query.build).withHttpCredentials)
  }

  /**
    * Refresh the given index
    */
  def refresh(index: IndexLabel): IO[Boolean] =
    client.run(Post(endpoint / index.value / refreshPath).withHttpCredentials) {
      case resp if resp.status.isSuccess() => discardEntity(resp).as(true)
    }

  /**
    * Obtain the mapping of the given index
    */
  def mapping(index: IndexLabel): IO[Json] =
    client.toJson(Get(endpoint / index.value / mapping).withHttpCredentials)

  /**
    * Creates a point-in-time to be used in further searches
    *
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/point-in-time-api.html
    * @param index
    *   the target index
    * @param keepAlive
    *   extends the time to live of the corresponding point in time
    */
  def createPointInTime(index: IndexLabel, keepAlive: FiniteDuration): IO[PointInTime] = {
    val pitEndpoint = (endpoint / index.value / pit).withQuery(Uri.Query("keep_alive" -> s"${keepAlive.toSeconds}s"))
    client.fromJsonTo[PointInTime](Post(pitEndpoint).withHttpCredentials)
  }

  /**
    * Deletes the given point-in-time
    *
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/point-in-time-api.html
    */
  def deletePointInTime(pointInTime: PointInTime): IO[Unit] =
    client.run(Delete(endpoint / pit, pointInTime.asJson).withHttpCredentials) {
      case resp if resp.status.isSuccess() => discardEntity(resp)
    }

  def createAlias(indexAlias: IndexAlias): IO[Unit] = {
    val aliasPayload = Json.obj(
      "index"   := indexAlias.index.value,
      "alias"   := indexAlias.alias.value,
      "routing" := indexAlias.routing,
      "filter"  := indexAlias.filter
    )
    aliasAction(Json.obj("add" := aliasPayload))
  }

  def removeAlias(index: IndexLabel, alias: IndexLabel): IO[Unit] = {
    val aliasPayload = Json.obj(
      "index" := index.value,
      "alias" := alias.value
    )
    aliasAction(Json.obj("remove" := aliasPayload))
  }

  private def aliasAction(aliasAction: Json) = {
    val aliasWrap = Json.obj("actions" := Json.arr(aliasAction))
    client.run(Post(endpoint / aliasPath, aliasWrap).withHttpCredentials) {
      case resp if resp.status.isSuccess() => discardEntity(resp)
    }
  }

  private def discardEntity(resp: HttpResponse) =
    IO.delay(resp.discardEntityBytes()) >> IO.unit

  private def indexPathAndQuery(indices: Set[String], query: QueryBuilder): (String, QueryBuilder) = {
    val indexPath = indices.mkString(",")
    if (indexPath.length < maxIndexPathLength) (indexPath, query)
    else (allIndexPath, query.withIndices(indices))
  }

  implicit private val resolvedServiceDescriptionDecoder: Decoder[ResolvedServiceDescription] =
    Decoder.instance { hc =>
      hc.downField("version").get[String]("number").map(ServiceDescription(serviceName, _))
    }

}

object ElasticSearchClient {

  private val emptyResults = json"""{
                                     "hits": {
                                        "hits": [],
                                        "total": {
                                         "relation": "eq",
                                           "value": 0
                                        }
                                     }
                                   }"""

  sealed trait Refresh {
    def value: String
  }

  object Refresh {
    case object True extends Refresh {
      override def value: String = "true"
    }

    case object False extends Refresh {
      override def value: String = "false"
    }

    case object WaitFor extends Refresh {
      override def value: String = "wait_for"
    }
  }

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
        case Right(list)   => Right(ScoredSearchResults(Hits.fetchTotal(json), maxScore, list, token(json)))
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
      }
    }

  private val decodeUnscoredResults: Decoder[SearchResults[JsonObject]] =
    Decoder.decodeJsonObject.emap { json =>
      queryResults(json, scored = false) match {
        case Right(list)   => Right(UnscoredSearchResults(Hits.fetchTotal(json), list, token(json)))
        case Left(errJson) => Left(s"Could not decode source from value '$errJson'")
      }
    }

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

  /**
    * Elasticsearch response after a bulk request
    */
  sealed trait BulkResponse extends Product with Serializable

  object BulkResponse {

    /**
      * No indexing error were returned by elasticsearch
      */
    final case object Success extends BulkResponse

    /**
      * At least one indexing error has been returned by elasticsearch
      * @param items
      *   the different outcomes indexed by document id (So we only keep the last outcome if a document id is repeated
      *   in a query but this is ok in the Delta context)
      */
    final case class MixedOutcomes(items: Map[String, Outcome]) extends BulkResponse

    object MixedOutcomes {

      /**
        * Outcome returned by Elasticsearch for a single document
        */
      sealed trait Outcome extends Product with Serializable

      object Outcome {

        /**
          * The document has been properly indexed
          */
        final case object Success extends Outcome

        /**
          * The document could not be indexed
          * @param json
          *   the reason returned by the Elasticsearch API
          */
        final case class Error(json: JsonObject) extends Outcome

        private val success: Outcome = Success
        private val Operations       = List("index", "delete", "update", "create")

        private[client] def from(hcursor: HCursor): Decoder.Result[(String, Outcome)] =
          Operations
            .collectFirstSome(hcursor.downField(_).success)
            .toRight(DecodingFailure(s"Operation type was not one of ${Operations.mkString(", ")}.", hcursor.history))
            .flatMap { c =>
              c.get[String]("_id").map {
                _ -> c.get[JsonObject]("error").fold(_ => Outcome.success, Outcome.Error)
              }
            }
      }
    }

    private val invalidResponse                                                   = Left(DecodingFailure(s"Bulk result did not provide a json response.", List.empty))
    private[client] def apply(err: HttpClientError): Decoder.Result[BulkResponse] =
      err.jsonBody.map(apply).getOrElse(invalidResponse)

    private[client] def apply(json: Json): Decoder.Result[BulkResponse] = {
      json.hcursor.get[Boolean]("errors").flatMap { hasErrors =>
        if (hasErrors) {
          val cursor = json.hcursor.downField("items")
          cursor.focus
            .flatMap(_.asArray)
            .map {
              _.foldLeftM(Map.empty[String, Outcome]) { case (acc, item) =>
                Outcome.from(item.hcursor).map { res =>
                  acc + res
                }
              }.map(MixedOutcomes(_))
            }
            .getOrElse(Left(DecodingFailure(s"Bulk result does not contain a `items` array property.", cursor.history)))
        } else
          Right(Success)
      }
    }
  }
}
