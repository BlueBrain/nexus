package ai.senscience.nexus.delta.plugins.archive

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin

/**
  * The archive plugin entrypoint.
  */
object ArchivePlugin extends Plugin {

  override def stop(): IO[Unit] = IO.unit
}
