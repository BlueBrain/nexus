package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

/**
  * The archive plugin entrypoint.
  */
class ArchivePlugin() extends Plugin {

  // TODO: plug in the routes here
  override def route: Option[Route] = None

  override def stop(): Task[Unit] = Task.unit
}
