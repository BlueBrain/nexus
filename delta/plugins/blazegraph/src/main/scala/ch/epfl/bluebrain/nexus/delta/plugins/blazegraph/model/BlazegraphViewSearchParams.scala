package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.{schema => blazegraphSchema}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef

/**
  * Search parameters for Blazegraph views.
  *
  * @param project
  *   the optional parent project of the views
  * @param deprecated
  *   the optional deprecation status of the views
  * @param rev
  *   the optional revision of the views
  * @param createdBy
  *   the optional subject who created the views
  * @param updatedBy
  *   the optional subject who last updated the views
  * @param types
  *   the collection of types to consider, where empty implies all views
  * @param filter
  *   an additional resource filter
  */
final case class BlazegraphViewSearchParams(
    project: Option[ProjectRef] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Long] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: Set[Iri] = Set.empty[Iri],
    filter: BlazegraphView => Boolean
) extends SearchParams[BlazegraphView] {

  override val schema: Option[ResourceRef] = Some(blazegraphSchema)

  override def matches(resource: ViewResource): Boolean =
    super.matches(resource) &&
      project.forall(_ == resource.value.project)
}
