package ch.epfl.bluebrain.nexus.delta.sdk.model

import org.apache.jena.iri.IRI

/**
  * Search parameters to filter resources.
  *
  * @param deprecated the optional deprecation status of the resources
  * @param rev        the optional revision of the resources
  * @param createdBy  the optional identity id who created the resource
  * @param updatedBy  the optional identity id who updated the resource
  * @param types      the optional types of the resources
  * @param schemas    the optional schemas of the resources
  */
final case class SearchParams(
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[IRI] = None,
    updatedBy: Option[IRI] = None,
    types: Set[IRI] = Set.empty,
    schemas: Set[ResourceRef] = Set.empty
)

object SearchParams {

  final val none: SearchParams = SearchParams()
}
