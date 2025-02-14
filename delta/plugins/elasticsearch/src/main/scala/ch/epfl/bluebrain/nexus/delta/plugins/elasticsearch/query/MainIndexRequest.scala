package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.query

import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ResourcesSearchParams
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SortList

/**
  * Search request on the main index
  */
final case class MainIndexRequest(params: ResourcesSearchParams, pagination: Pagination, sort: SortList)
    extends Product
    with Serializable
