package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object GraphAnalyticsPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
