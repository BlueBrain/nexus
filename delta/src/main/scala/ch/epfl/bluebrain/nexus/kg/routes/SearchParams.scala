package ch.epfl.bluebrain.nexus.kg.routes

import ch.epfl.bluebrain.nexus.commons.search.SortList
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Search parameters to filter resources
  *
  * @param deprecated the optional deprecation status of the resources
  * @param rev        the optional revision of the resources
  * @param schema     the optional schema of the resources
  * @param createdBy  the optional identity id who created the resource
  * @param updatedBy  the optional identity id who updated the resource
  * @param types      the optional types of the resources
  * @param sort       the sorting response
  * @param id         the optional id of the resources
  * @param q          the optional full text search string
  */
final case class SearchParams(
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    schema: Option[AbsoluteIri] = None,
    createdBy: Option[AbsoluteIri] = None,
    updatedBy: Option[AbsoluteIri] = None,
    types: List[AbsoluteIri] = List.empty,
    sort: SortList = SortList.Empty,
    id: Option[AbsoluteIri] = None,
    q: Option[String] = None
)
