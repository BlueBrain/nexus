package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object CompositeViewsPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
