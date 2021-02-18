package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

/**
  * Search parameters for any generic resource type.
  *
  * @param id         the optional id status of the resource
  * @param deprecated the optional deprecation status of the resource
  * @param rev        the optional revision of the resource
  * @param createdBy  the optional subject who created the resource
  * @param updatedBy  the optional subject who last updated the resource
  * @param types      the collection of types to consider, where empty implies all resource types
  * @param schema     schema to consider, where empty implies any schema
  * @param q          a full text search query parameter
  */
final case class ResourcesSearchParams(
    id: Option[Iri] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: List[Iri] = List.empty,
    schema: Option[ResourceRef] = None,
    q: Option[String] = None
)
