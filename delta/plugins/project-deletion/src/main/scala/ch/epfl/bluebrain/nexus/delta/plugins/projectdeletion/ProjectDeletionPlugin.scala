package ch.epfl.bluebrain.nexus.delta.plugins.projectdeletion

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object ProjectDeletionPlugin extends Plugin {
  override def stop(): IO[Unit] = IO.unit
}
