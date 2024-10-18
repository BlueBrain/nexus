package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph.supervision

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.views.ViewRef

trait ViewByNamespace {
  def get: IO[Map[String, ViewRef]]
}
