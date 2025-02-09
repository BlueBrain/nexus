package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import akka.http.scaladsl.model.Uri
import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.http.HttpClientError
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.{ElasticSearchClient, Hits, QueryBuilder}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.config.DefaultIndexConfig
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query.ElasticSearchQueryError.ElasticSearchClientError
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{AggregationResult, SearchResults}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, JsonObject}

/**
  * Allow to list resources from the default Elasticsearch index
  */
trait DefaultIndexQuery {

  /**
    * Retrieves a list of resources from the provided search request on the set of projects
    */
  def list(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[SearchResults[JsonObject]]

  /**
    * Retrieves aggregations for the provided search request on the set of projects
    */
  def aggregate(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[AggregationResult]
}

object DefaultIndexQuery {

  private val excludeOriginalSource = "_source_excludes" -> "_original_source"

  def apply(
      client: ElasticSearchClient,
      config: DefaultIndexConfig
  )(implicit baseUri: BaseUri): DefaultIndexQuery = new DefaultIndexQuery {

    override def list(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[SearchResults[JsonObject]] = {
      val query =
        QueryBuilder(request.params, projects).withPage(request.pagination).withTotalHits(true).withSort(request.sort)
      client
        .search(query, Set(config.index.value), Uri.Query(excludeOriginalSource))
        .adaptError { case e: HttpClientError => ElasticSearchClientError(e) }
    }

    override def aggregate(request: DefaultIndexRequest, projects: Set[ProjectRef]): IO[AggregationResult] = {
      val query = QueryBuilder(request.params, projects).aggregation(config.bucketSize)
      client
        .searchAs[AggregationResult](query, config.index.value, Uri.Query.Empty)
        .adaptError { case e: HttpClientError => ElasticSearchClientError(e) }
    }
  }

  implicit val aggregationDecoder: Decoder[AggregationResult] =
    Decoder.decodeJsonObject.emap { result =>
      result.asJson.hcursor
        .downField("aggregations")
        .focus
        .flatMap(_.asObject) match {
        case Some(aggs) => Right(AggregationResult(Hits.fetchTotal(result), aggs))
        case None       => Left("The response did not contain a valid 'aggregations' field.")
      }
    }
}
