package ch.epfl.bluebrain.nexus.delta.plugins.blazegraph

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object BlazegraphPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
