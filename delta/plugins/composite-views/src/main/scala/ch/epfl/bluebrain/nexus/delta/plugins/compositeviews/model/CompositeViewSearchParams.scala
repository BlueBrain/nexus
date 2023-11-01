package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.SearchParams
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ProjectRef, ResourceRef}

/**
  * Search parameters for Composite views.
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
final case class CompositeViewSearchParams(
    project: Option[ProjectRef] = None,
    deprecated: Option[Boolean] = None,
    rev: Option[Int] = None,
    createdBy: Option[Subject] = None,
    updatedBy: Option[Subject] = None,
    types: Set[Iri] = Set.empty,
    filter: CompositeView => IO[Boolean]
) extends SearchParams[CompositeView] {

  override val schema: Option[ResourceRef] = Some(model.schema)

  override def matches(resource: ViewResource): IO[Boolean] =
    super.matches(resource).map(_ && project.forall(_ == resource.value.project))

}
