package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.model.BlazegraphView.{AggregateBlazegraphView, IndexingBlazegraphView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sourcing.config.ExternalIndexingConfig
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews.namespace
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef

object BlazegraphViewRefVisitor {

  /**
    * Constructs a [[ViewRefVisitor]] for blazegraph views
    */
  def apply(views: BlazegraphViews, config: ExternalIndexingConfig) =
    new ViewRefVisitor(views.fetch(_, _).map { view =>
      view.value match {
        case v: IndexingBlazegraphView  =>
          IndexedVisitedView(ViewRef(v.project, v.id), v.permission, namespace(v.uuid, view.rev, config))
        case v: AggregateBlazegraphView =>
          AggregatedVisitedView(ViewRef(v.project, v.id), v.views)
      }
    })

}
