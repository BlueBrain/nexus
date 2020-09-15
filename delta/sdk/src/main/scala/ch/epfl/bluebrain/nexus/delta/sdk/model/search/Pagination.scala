package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import io.circe.Json

/**
  * Base request pagination data type.
  *
  */
trait Pagination {

  /**
    *
    * @return size the maximum number of results per page
    */
  def size: Int
}

/**
  * Request pagination data type using `from`.
  *
  * @param from the start offset
  * @param size the maximum number of results per page
  */
final case class FromPagination(from: Int, size: Int) extends Pagination

/**
  * Request pagination data type using `search_after`.
  *
  * @param searchAfter  [[Json]] to use as ElasticSearch `search_after` field. Should be directly taken from `sort` field
  *                     in the search results.
  * @param size         the maximum number of results per page
  */
final case class SearchAfterPagination(searchAfter: Json, size: Int) extends Pagination

object Pagination {

  def apply(size: Int): Pagination                    = FromPagination(0, size)
  def apply(from: Int, size: Int): Pagination         = FromPagination(from, size)
  def apply(searchAfter: Json, size: Int): Pagination = SearchAfterPagination(searchAfter, size)
}
