package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

object ProjectDeletionPlugin extends Plugin {
  override def stop(): Task[Unit] = Task.unit
}
