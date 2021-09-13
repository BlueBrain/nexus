package ch.epfl.bluebrain.nexus.delta.plugins.search

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object SearchPlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
