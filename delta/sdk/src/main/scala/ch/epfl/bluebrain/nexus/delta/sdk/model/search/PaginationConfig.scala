package ch.epfl.bluebrain.nexus.delta.sdk.model.search

/**
  * Pagination configuration.
  *
  * @param defaultSize the default number of results per page
  * @param sizeLimit   the maximum number of results per page
  * @param fromLimit   the maximum value of `from` parameter
  */
final case class PaginationConfig(defaultSize: Int, sizeLimit: Int, fromLimit: Int)
