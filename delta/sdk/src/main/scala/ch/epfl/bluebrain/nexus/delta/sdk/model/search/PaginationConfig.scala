package ch.epfl.bluebrain.nexus.delta.sdk.model.search

import pureconfig.ConfigReader
import pureconfig.generic.semiauto.deriveReader

/**
  * Pagination configuration.
  *
  * @param defaultSize the default number of results per page
  * @param sizeLimit   the maximum number of results per page
  * @param fromLimit   the maximum value of `from` parameter
  */
final case class PaginationConfig(defaultSize: Int, sizeLimit: Int, fromLimit: Int)

object PaginationConfig {
  implicit final val paginationConfigReader: ConfigReader[PaginationConfig] =
    deriveReader[PaginationConfig]
}
