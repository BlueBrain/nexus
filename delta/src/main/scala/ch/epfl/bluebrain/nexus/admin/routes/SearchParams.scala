package ch.epfl.bluebrain.nexus.admin.routes

import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri

/**
  * Search parameters to filter resources
  *
  * @param organizationLabel the optional organization label of the resources
  * @param projectLabel      the optional project label of the resources
  * @param deprecated        the optional deprecation status of the resources
  * @param rev               the optional revision of the resources
  * @param createdBy         the optional identity id who created the resource
  * @param updatedBy         the optional identity id who updated the resource
  * @param types             the optional types of the resources
  */
final case class SearchParams(
    organizationLabel: Option[Field] = None,
    projectLabel: Option[Field] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[AbsoluteIri] = None,
    updatedBy: Option[AbsoluteIri] = None,
    types: Set[AbsoluteIri] = Set.empty
)

object SearchParams {
  val empty = SearchParams()

  /**
    * A field with a matching strategy
    *
    * @param value      the field
    * @param exactMatch the matching strategy
    */
  final case class Field(value: String, exactMatch: Boolean = false) {

    def matches(that: String): Boolean =
      if (exactMatch) that == value else that.toLowerCase.contains(value.toLowerCase)
  }
}
