package ch.epfl.bluebrain.nexus.routes

import akka.http.scaladsl.model.Uri

/**
  * Search parameters to filter resources
  *
  * @param deprecated        the optional deprecation status of the resources
  * @param rev               the optional revision of the resources
  * @param createdBy         the optional identity id who created the resource
  * @param updatedBy         the optional identity id who updated the resource
  * @param types             the optional types of the resources
  */
final case class SearchParams(
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[Uri] = None,
    updatedBy: Option[Uri] = None,
    types: Set[Uri] = Set.empty
)

object SearchParams {
  val empty = SearchParams()
}
