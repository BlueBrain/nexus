package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client.QueryBuilder.allFields
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, SearchAfterPagination}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, Sort, SortList}
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

final case class QueryBuilder private[client] (private val query: JsonObject) {

  private val trackTotalHits                                                       = "track_total_hits"
  private val searchAfter                                                          = "search_after"
  private val source                                                               = "_source"
  implicit private def subjectEncoder(implicit baseUri: BaseUri): Encoder[Subject] = Identity.subjectIdEncoder

  implicit private val sortEncoder: Encoder[Sort] =
    Encoder.encodeJson.contramap(sort => Json.obj(sort.value -> sort.order.asJson))

  /**
    * Adds pagination to the current payload
    *
    * @param page the pagination information
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
    * Defines what fields are going to be present in the response
    */
  def withFields(fields: Set[String]): QueryBuilder =
    if (fields.isEmpty) this
    else copy(query.add(source, fields.asJson))

  /**
    * Adds sort to the current payload
    */
  def withSort(sortList: SortList): QueryBuilder =
    if (sortList.isEmpty) this
    else copy(query.add("sort", sortList.values.asJson))

  /**
    * Filters by the passed ''params''
    */
  def withFilters(params: ResourcesSearchParams)(implicit baseUri: BaseUri): QueryBuilder = {
    val (includeTypes, excludeTypes) = params.types.partition(_.include)
    QueryBuilder(
      query deepMerge queryPayload(
        mustTerms = includeTypes.map(tpe => term(keywords.tpe, tpe.value)) ++
          params.id.map(term(keywords.id, _)) ++
          params.q.map(`match`(allFields, _)) ++
          params.schema.map(term(nxv.constrainedBy.prefix, _)) ++
          params.deprecated.map(term(nxv.deprecated.prefix, _)) ++
          params.rev.map(term(nxv.rev.prefix, _)) ++
          params.createdBy.map(term(nxv.createdBy.prefix, _)) ++
          params.updatedBy.map(term(nxv.updatedBy.prefix, _)),
        mustNotTerms = excludeTypes.map(tpe => term(keywords.tpe, tpe.value)),
        withScore = params.q.isDefined
      )
    )
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
          .addIfExists("must_not", Option.when(mustNotTerms.nonEmpty)(mustNotTerms))
      )
    )
  }

  private def term[A: Encoder](k: String, value: A): JsonObject    =
    JsonObject("term" -> Json.obj(k -> value.asJson))

  private def `match`[A: Encoder](k: String, value: A): JsonObject =
    JsonObject("match" -> Json.obj(k -> value.asJson))

  def build: JsonObject                                            = query
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
