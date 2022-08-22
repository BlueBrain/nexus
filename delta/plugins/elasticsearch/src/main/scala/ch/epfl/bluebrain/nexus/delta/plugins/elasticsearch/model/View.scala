package ch.epfl.bluebrain.nexus.delta.plugins.elasticsearch.model

import ch.epfl.bluebrain.nexus.delta.sdk.permissions.model.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.views.model.ViewRef

sealed trait View extends Product with Serializable

object View {

  final case class IndexingView(ref: ViewRef, index: String, permission: Permission) extends View

  final case class AggregateView(views: List[IndexingView]) extends View {

    def +(view: IndexingView): AggregateView          = AggregateView(views :+ view)
    def ++(view: Option[IndexingView]): AggregateView = AggregateView(views ++ view)

  }

  object AggregateView {

    val empty = AggregateView(List.empty)

  }

}
