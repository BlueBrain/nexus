package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object CompositeViewsPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
