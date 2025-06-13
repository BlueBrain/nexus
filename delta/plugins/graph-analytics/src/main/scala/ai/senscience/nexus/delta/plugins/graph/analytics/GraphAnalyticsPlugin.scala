package ai.senscience.nexus.delta.plugins.graph.analytics

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object GraphAnalyticsPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
