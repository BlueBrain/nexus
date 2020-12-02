package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import io.circe.Json

/**
  * Base request pagination data type.
  */
trait Pagination {

  /**
    * @return size the maximum number of results per page
    */
  def size: Int
}

object Pagination {

  // Keywords used in directives and serialization for pagination
  val from: String  = "from"
  val size: String  = "size"
  val after: String = "after"

  /**
    * To retrieve all result on a single page
    */
  val OnePage: FromPagination = FromPagination(0, Integer.MAX_VALUE)

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

  /**
    * Creates a [[FromPagination]] starting from the offset value ''0'' and with the passed ''size''
    */
  def apply(size: Int): Pagination =
    FromPagination(0, size)

  /**
    * Creates a [[FromPagination]] starting from the passed offset value ''from'' and with the passed ''size''
    */
  def apply(from: Int, size: Int): Pagination =
    FromPagination(from, size)

  /**
    * Creates a [[SearchAfterPagination]] using the passed ''searchAfter'' value and with the passed ''size''
    */
  def apply(searchAfter: Json, size: Int): Pagination = SearchAfterPagination(searchAfter, size)
}
