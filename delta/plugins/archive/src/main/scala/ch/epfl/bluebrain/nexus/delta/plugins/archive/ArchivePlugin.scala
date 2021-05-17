package ch.epfl.bluebrain.nexus.delta.plugins.archive

import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

/**
  * The archive plugin entrypoint.
  */
object ArchivePlugin extends Plugin {

  override def stop(): Task[Unit] = Task.unit
}
