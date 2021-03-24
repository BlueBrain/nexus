package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model

import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeView.RebuildStrategy
import ch.epfl.bluebrain.nexus.delta.sdk.model.NonEmptySet

/**
  * The configuration for a composite view.
  *
  * @param sources          the collection of sources for the view
  * @param projections      the collection of projections for the view
  * @param rebuildStrategy  the rebuild strategy of the view
  */
final case class CompositeViewValue(
    sources: NonEmptySet[CompositeViewSource],
    projections: NonEmptySet[CompositeViewProjection],
    rebuildStrategy: RebuildStrategy
)
