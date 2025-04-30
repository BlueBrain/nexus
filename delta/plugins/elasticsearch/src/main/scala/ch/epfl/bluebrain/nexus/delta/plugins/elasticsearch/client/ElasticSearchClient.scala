package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.dependency.ComponentDescription.ServiceDescription.ResolvedServiceDescription
import ch.epfl.bluebrain.nexus.delta.kernel.http.client.middleware.BasicAuth
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UrlUtils
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.ElasticSearchClient.*
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchClientError.{ElasticsearchActionError, ElasticsearchCreateIndexError, ElasticsearchQueryError, ElasticsearchWriteError, ScriptCreationDismissed}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.ResultEntry.{ScoredResultEntry, UnscoredResultEntry}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchResults.{ScoredSearchResults, UnscoredSearchResults}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{ResultEntry, SearchResults, SortList}
import ch.epfl.bluebrain.nexus.delta.sdk.syntax.*
import io.circe.*
import io.circe.literal.*
import io.circe.syntax.*
import org.http4s.Method.*
import org.http4s.circe.*
import org.http4s.circe.CirceEntityEncoder.*
import org.http4s.circe.CirceEntityDecoder.*
import org.http4s.client.Client
import org.http4s.client.dsl.io.*
import org.http4s.client.middleware.GZip
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.http4s.{BasicCredentials, EntityEncoder, MediaType, Query, Status, Uri}

import scala.concurrent.duration.*

/**
  * A client that provides some of the functionality of the elasticsearch API.
  */
final class ElasticSearchClient(client: Client[IO], endpoint: Uri, maxIndexPathLength: Int) {

  private val serviceName            = "elasticsearch"
  private val scriptPath             = "_scripts"
  private val docPath                = "_doc"
  private val allIndexPath           = "_all"
  private val aliasPath              = "_aliases"
  private val bulkPath               = "_bulk"
  private val refreshPath            = "_refresh"
  private val indexTemplate          = "_index_template"
  private val tasksPath              = "_tasks"
  private val waitForCompletion      = "wait_for_completion"
  private val refreshParam           = "refresh"
  private val ignoreUnavailable      = "ignore_unavailable"
  private val allowNoIndices         = "allow_no_indices"
  private val deleteByQueryPath      = "_delete_by_query"
  private val updateByQueryPath      = "_update_by_query"
  private val countPath              = "_count"
  private val searchPath             = "_search"
  private val source                 = "_source"
  private val mapping                = "_mapping"
  private val pit                    = "_pit"
  private val newLine                = System.lineSeparator()
  private val `application/x-ndjson` =
    new MediaType("application", "x-ndjson", compressible = true, fileExtensions = List("json"))
  private val defaultQuery           = Map(ignoreUnavailable -> "true", allowNoIndices -> "true")
  private val defaultUpdateByQuery   = defaultQuery + (waitForCompletion -> "false")
  private val defaultDeleteByQuery   = defaultQuery + (waitForCompletion -> "true")

  /**
    * Fetches the service description information (name and version)
    */
  def serviceDescription: IO[ServiceDescription] =
    client
      .expect[ResolvedServiceDescription](endpoint)
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
    client.status(HEAD(endpoint / index.value)).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(ElasticsearchActionError(status, "exists"))
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
        val request = PUT(payload, endpoint / index.value)
        client.expectOr[Json](request)(ElasticsearchCreateIndexError(_)).as(true)
      case true  => IO.pure(false)
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
    client.status(PUT(template, endpoint / indexTemplate / name)).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(ElasticsearchActionError(status, "createTemplate"))
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
    client.status(DELETE(endpoint / index.value)).flatMap {
      case Status.Ok       => IO.pure(true)
      case Status.NotFound => IO.pure(false)
      case status          => IO.raiseError(ElasticsearchActionError(status, "deleteIndex"))
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
    client.successful(PUT(payload, endpoint / index.value / docPath / UrlUtils.encode(id))).void

  /**
    * Creates a bulk update with the operations defined on the provided ''ops'' argument.
    *
    * @param actions
    *   the list of operations to be included in the bulk update
    * @param refresh
    *   the value for the `refresh` Elasticsearch parameter
    */
  def bulk(actions: Seq[ElasticSearchAction], refresh: Refresh = Refresh.False): IO[BulkResponse] = {
    if (actions.isEmpty) IO.pure(BulkResponse.Success)
    else {
      val payload      = actions.map(_.payload).mkString("", newLine, newLine)
      val bulkEndpoint = (endpoint / bulkPath).withQueryParam(refreshParam, refresh.value)
      val request      = POST(payload, bulkEndpoint, `Content-Type`(`application/x-ndjson`))(EntityEncoder.stringEncoder)
      client.expectOr[BulkResponse](request)(ElasticsearchWriteError(_))
    }
  }

  /**
    * Creates a script on Elasticsearch with the passed ''id'' and ''content''
    */
  def createScript(id: String, content: String): IO[Unit] = {
    val payload = Json.obj("script" -> Json.obj("lang" -> "painless".asJson, "source" -> content.asJson))
    val request = PUT(payload, endpoint / scriptPath / UrlUtils.encode(id))
    client.expectOr[Json](request)(ScriptCreationDismissed(_)).void
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
    val updateEndpoint = (endpoint / indexPath / updateByQueryPath).withQueryParams(defaultUpdateByQuery)
    val request        = POST(q.build, updateEndpoint)
    for {
      response <- client.expect[UpdateByQueryResponse](request)
      taskReq   = GET((endpoint / tasksPath / response.task).withQueryParam(waitForCompletion, "true"))
      _        <- client.expect[Json](taskReq)
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
    val deleteEndpoint = (endpoint / index.value / deleteByQueryPath).withQueryParams(defaultDeleteByQuery)
    val req            = POST(query, deleteEndpoint)
    client.expect[Json](req).void
  }

  /**
    * Get the source of the Elasticsearch document
    * @param index
    *   the index to look in
    * @param id
    *   the identifier of the document
    */
  def getSource[R: Decoder](index: IndexLabel, id: String): IO[Option[R]] = {
    val sourceEndpoint = endpoint / index.value / source / id
    client.expectOption[R](GET(sourceEndpoint))
  }

  /**
    * Returns the number of document in a given index
    * @param index
    *   the index to use
    */
  def count(index: String): IO[Long] =
    client.expect[Count](GET(endpoint / index / countPath)).map(_.value)

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
      val searchEndpoint = (endpoint / indexPath / searchPath).withQueryParams(defaultQuery ++ qp.params)
      val payload        = q.withSort(sort).withTotalHits(true).build
      client.expectOr[Json](POST(payload, searchEndpoint))(ElasticsearchQueryError(_))
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
  def searchAs[T: Decoder](
      query: QueryBuilder,
      indices: Set[String],
      qp: Query
  )(onEmpty: => T): IO[T] =
    if (indices.isEmpty)
      IO.pure(onEmpty)
    else {
      val (indexPath, q) = indexPathAndQuery(indices, query)
      val searchEndpoint = (endpoint / indexPath / searchPath).withQueryParams(defaultQuery ++ qp.params)
      client.expect[T](POST(q.build, searchEndpoint))
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
  def searchAs[T: Decoder](
      query: QueryBuilder,
      index: String,
      qp: Query
  ): IO[T] = {
    val searchEndpoint = (endpoint / index / searchPath).withQueryParams(defaultQuery ++ qp.params)
    client.expect[T](POST(query.build, searchEndpoint))
  }

  /**
    * Refresh the given index
    */
  def refresh(index: IndexLabel): IO[Boolean] = client.successful(POST(endpoint / index.value / refreshPath))

  /**
    * Obtain the mapping of the given index
    */
  def mapping(index: IndexLabel): IO[Json] = client.expect[Json](GET(endpoint / index.value / mapping))

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
    val pitEndpoint = (endpoint / index.value / pit).withQueryParam("keep_alive", s"${keepAlive.toSeconds}s")
    client.expect[PointInTime](POST(pitEndpoint))
  }

  /**
    * Deletes the given point-in-time
    *
    * @see
    *   https://www.elastic.co/guide/en/elasticsearch/reference/current/point-in-time-api.html
    */
  def deletePointInTime(pointInTime: PointInTime): IO[Unit] =
    client.successful(DELETE(pointInTime, endpoint / pit)).void

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
    client
      .expectOr[Json](POST(aliasWrap, endpoint / aliasPath)) { r =>
        IO.pure(ElasticsearchActionError(r.status, "createAlias"))
      }
      .void
  }

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

  private val logger = Logger[this.type]

  def apply(
      endpoint: Uri,
      credentials: Option[BasicCredentials],
      maxIndexPathLength: Int
  ): Resource[IO, ElasticSearchClient] =
    EmberClientBuilder
      .default[IO]
      .withLogger(logger)
      .build
      .map { client =>
        val authGzipClient = GZip()(BasicAuth(credentials)(client))
        new ElasticSearchClient(authGzipClient, endpoint, maxIndexPathLength)
      }

  private val emptyResults = json"""{
                                     "hits": {
                                        "hits": [],
                                        "total": {
                                         "relation": "eq",
                                           "value": 0
                                        }
                                     }
                                   }"""

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
}
