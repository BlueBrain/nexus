package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination.{FromPagination, SearchAfterPagination}
import ch.epfl.bluebrain.nexus.delta.kernel.search.{Pagination, TimeRange}
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams.{Type, TypeOperator}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Sort, SortList}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import io.circe.literal.JsonStringContext
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

final case class QueryBuilder private[client] (private val query: JsonObject) {

  private val trackTotalHits = "track_total_hits"
  private val searchAfter    = "search_after"

  implicit private def subjectEncoder(implicit baseUri: BaseUri): Encoder[Subject] = IriEncoder.jsonEncoder[Subject]

  implicit private val sortEncoder: Encoder[Sort] =
    Encoder.encodeJson.contramap(sort => Json.obj(sort.value -> sort.order.asJson))

  /**
    * Adds pagination to the current payload
    *
    * @param page
    *   the pagination information
    */
  def withPage(page: Pagination): QueryBuilder    =
    page match {
      case FromPagination(from, size)      => copy(query.add("from", from.asJson).add("size", size.asJson))
      case SearchAfterPagination(sa, size) => copy(query.add(searchAfter, sa.asJson).add("size", size.asJson))
    }

  /**
    * Enables or disables the tracking of total hits count
    */
  def withTotalHits(value: Boolean): QueryBuilder =
    copy(query.add(trackTotalHits, value.asJson))

  /**
    * Adds sort to the current payload
    */
  def withSort(sortList: SortList): QueryBuilder =
    if (sortList.isEmpty) this
    else copy(query.add("sort", sortList.values.asJson))

  private def typesTerms(typeOperator: TypeOperator, types: List[Type]) = {
    val terms = types.map(tpe => term(keywords.tpe, tpe.value))

    if (types.isEmpty) {
      Nil
    } else {
      typeOperator match {
        case TypeOperator.And => List(and(terms: _*))
        case TypeOperator.Or  => List(or(terms: _*))
      }
    }
  }

  /**
    * Filters by the passed ''params''
    */
  def withFilters(params: ResourcesSearchParams)(implicit baseUri: BaseUri): QueryBuilder = {
    val (includeTypes, excludeTypes) = params.types.partition(_.include)
    QueryBuilder(
      query deepMerge queryPayload(
        mustTerms = typesTerms(params.typeOperator, includeTypes) ++
          params.locate.map { l => or(term(keywords.id, l), term(nxv.self.prefix, l)) } ++
          params.id.map(term(keywords.id, _)) ++
          params.q.map(multiMatch) ++
          params.schema.map(term(nxv.constrainedBy.prefix, _)) ++
          params.deprecated.map(term(nxv.deprecated.prefix, _)) ++
          params.rev.map(term(nxv.rev.prefix, _)) ++
          params.createdBy.map(term(nxv.createdBy.prefix, _)) ++
          range(nxv.createdAt.prefix, params.createdAt) ++
          params.updatedBy.map(term(nxv.updatedBy.prefix, _)) ++
          range(nxv.updatedAt.prefix, params.updatedAt) ++
          params.tag.map(term(nxv.tags.prefix, _)),
        mustNotTerms = typesTerms(params.typeOperator.negate, excludeTypes),
        withScore = params.q.isDefined
      )
    )
  }

  private def or(terms: JsonObject*)  =
    JsonObject("bool" -> Json.obj("should" -> terms.asJson))

  private def and(terms: JsonObject*) =
    JsonObject("bool" -> Json.obj("must" -> terms.asJson))

  /**
    * Add indices filter to the query body
    */
  def withIndices(indices: Iterable[String]): QueryBuilder = {
    val filter   = Json
      .obj(
        "filter" -> terms("_index", indices).asJson
      )
    val newQuery = query("query") match {
      case None               => query.add("query", Json.obj("bool" -> filter))
      case Some(currentQuery) =>
        val boolQuery = Json.obj("must" -> currentQuery) deepMerge filter
        query.add("query", Json.obj("bool" -> boolQuery))
    }
    QueryBuilder(newQuery)
  }

  private def queryPayload(
      mustTerms: List[JsonObject],
      mustNotTerms: List[JsonObject],
      withScore: Boolean
  ): JsonObject = {
    val eval = if (withScore) "must" else "filter"
    JsonObject(
      "query" -> Json.obj(
        "bool" -> Json
          .obj(eval -> mustTerms.asJson)
          .addIfNonEmpty("must_not", mustNotTerms)
      )
    )
  }

  private def range(k: String, timeRange: TimeRange): Option[JsonObject] = {
    import TimeRange._
    def range(value: Json) = Some(JsonObject("range" -> Json.obj(k -> value)))
    timeRange match {
      case Anytime             => None
      case Before(value)       => range(Json.obj("lt" := value))
      case After(value)        => range(Json.obj("gt" := value))
      case Between(start, end) => range(Json.obj("gt" := start, "lt" := end))
    }
  }

  private def term[A: Encoder](k: String, value: A): JsonObject             =
    JsonObject("term" -> Json.obj(k -> value.asJson))

  private def terms[A: Encoder](k: String, values: Iterable[A]): JsonObject =
    JsonObject("terms" -> Json.obj(k -> values.asJson))

  /**
    * Defines a multi-match query. If the input [[q]] is an absolute IRI, then the `path_hierarchy` analyzer is used in
    * order to not split the IRI into tokens that are not meaningful.
    */
  private def multiMatch(q: String): JsonObject = {
    val iri      = Iri.absolute(q).toOption
    val payload  = JsonObject(
      "multi_match" -> Json.obj(
        "query"  -> iri.map(_.toString).getOrElse(q).asJson,
        "fields" -> json"""[ "*", "*.fulltext", "_tags", "_original_source", "_uuid" ]"""
      )
    )
    val analyzer = JsonObject(
      "multi_match" -> Json.obj("analyzer" := "path_hierarchy")
    )

    iri match {
      case Some(_) => payload deepMerge analyzer
      case None    => payload
    }
  }

  def aggregation(bucketSize: Int): QueryBuilder = {
    val aggregations =
      JsonObject(
        "aggs" := Json.obj(
          termAggregation("projects", "_project", bucketSize),
          termAggregation("types", "@type", bucketSize)
        ),
        "size" := 0
      )
    QueryBuilder(query deepMerge aggregations)
  }

  private def termAggregation(name: String, fieldName: String, bucketSize: Int) =
    name -> Json.obj("terms" -> Json.obj("field" := fieldName, "size" := bucketSize))

  def build: JsonObject = query
}

object QueryBuilder {

  /**
    * The elasticsearch schema parameter where all other fields are being copied to
    */
  final private[client] val allFields = "_all_fields"

  /**
    * An empty [[QueryBuilder]]
    */
  val empty: QueryBuilder = QueryBuilder(JsonObject.empty)

  /**
    * A [[QueryBuilder]] using the filter ''params''.
    */
  def apply(params: ResourcesSearchParams)(implicit baseUri: BaseUri): QueryBuilder =
    empty.withFilters(params)
}
