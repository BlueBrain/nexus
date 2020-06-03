package ch.epfl.bluebrain.nexus.iam.routes

import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

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
    createdBy: Option[AbsoluteIri] = None,
    updatedBy: Option[AbsoluteIri] = None,
    types: Set[AbsoluteIri] = Set.empty
)

object SearchParams {
  val empty = SearchParams()
}
