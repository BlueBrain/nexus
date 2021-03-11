package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef

/**
  * The configuration for a composite view.
  *
  * @param project          the project to which this view belongs
  * @param sources          the collection of sources for the view
  * @param projections      the collection of projections for the view
  * @param rebuildStrategy  the rebuild strategy of the view
  */
final case class CompositeViewValue(
    project: ProjectRef,
    sources: Set[CompositeViewSource],
    projections: Set[CompositeViewProjection],
    rebuildStrategy: RebuildStrategy
)
