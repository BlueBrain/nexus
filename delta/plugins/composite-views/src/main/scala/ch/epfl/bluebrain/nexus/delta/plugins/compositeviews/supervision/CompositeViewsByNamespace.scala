package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.supervision

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision.ViewByNamespace
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.CompositeViews
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.{commonNamespace, CompositeViewDef}
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.indexing.CompositeViewDef.ActiveViewDef
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef
import fs2.Stream

/**
  * Allows to get a mapping for the active composite view and their common namespace
  */
object CompositeViewsByNamespace {

  def apply(compositeViews: CompositeViews, prefix: String): ViewByNamespace =
    apply(compositeViews.currentViews.map(_.value), prefix)

  def apply(stream: Stream[IO, CompositeViewDef], prefix: String): ViewByNamespace = new ViewByNamespace {
    override def get: IO[Map[String, ViewRef]] = stream
      .fold(Map.empty[String, ViewRef]) {
        case (acc, view: ActiveViewDef) =>
          val namespace = commonNamespace(view.uuid, view.indexingRev, prefix)
          acc + (namespace -> view.ref)
        case (acc, _)                   => acc
      }
      .compile
      .last
      .map(_.getOrElse(Map.empty))
  }
}
