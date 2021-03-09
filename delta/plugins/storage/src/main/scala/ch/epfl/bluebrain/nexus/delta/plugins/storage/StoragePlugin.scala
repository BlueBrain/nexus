package ch.epfl.bluebrain.nexus.delta.plugins.storage

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object StoragePlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
