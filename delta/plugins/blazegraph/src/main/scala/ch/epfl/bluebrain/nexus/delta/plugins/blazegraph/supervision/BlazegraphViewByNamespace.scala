package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.BlazegraphViews
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.indexing.IndexingViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import fs2.Stream

/**
  * Allows to get a mapping for the active blazegraph views between their namespace and their view reference
  */
object BlazegraphViewByNamespace {

  def apply(blazegraphViews: BlazegraphViews): ViewByNamespace = apply(
    blazegraphViews.currentIndexingViews.map(_.value)
  )

  def apply(stream: Stream[IO, IndexingViewDef]): ViewByNamespace = new ViewByNamespace {
    override def get: IO[Map[String, ViewRef]] = stream
      .fold(Map.empty[String, ViewRef]) {
        case (acc, view: ActiveViewDef) => acc + (view.namespace -> view.ref)
        case (acc, _)                   => acc
      }
      .compile
      .last
      .map(_.getOrElse(Map.empty))
  }

}
