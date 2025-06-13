package ai.senscience.nexus.delta.plugins.search

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object SearchPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
