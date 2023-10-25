package ch.epfl.bluebrain.nexus.delta.plugins.storage

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

object StoragePlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
