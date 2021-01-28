package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.client

import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{FromPagination, SearchAfterPagination}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, Sort, SortList}
import io.circe.syntax._
import io.circe.{Encoder, Json, JsonObject}

final private[client] case class QueryBuilder(private val query: JsonObject) {

  private val trackTotalHits = "track_total_hits"
  private val searchAfter    = "search_after"
  private val source         = "_source"

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

  def build: JsonObject = query
}
