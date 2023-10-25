package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object BlazegraphPlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
