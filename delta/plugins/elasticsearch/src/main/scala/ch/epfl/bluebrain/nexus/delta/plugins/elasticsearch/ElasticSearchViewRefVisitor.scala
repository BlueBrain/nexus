package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch

import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.ElasticSearchViews.index
import ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model.ElasticSearchView.{AggregateElasticSearchView, IndexingElasticSearchView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRefVisitor.VisitedView.{AggregatedVisitedView, IndexedVisitedView}
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef

object ElasticSearchViewRefVisitor {

  /**
    * Constructs a [[ViewRefVisitor]] for elasticsearch views
    */
  def apply(views: ElasticSearchViews, prefix: String) =
    new ViewRefVisitor(views.fetch(_, _).map { view =>
      view.value match {
        case v: IndexingElasticSearchView  =>
          IndexedVisitedView(ViewRef(v.project, v.id), v.permission, index(v.uuid, view.rev.toInt, prefix))
        case v: AggregateElasticSearchView =>
          AggregatedVisitedView(ViewRef(v.project, v.id), v.views)
      }
    })

}
