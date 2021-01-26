package ch.epfl.bluebrain.nexus.delta.plugins.storage

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes.FilesRoutes
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.routes.StoragesRoutes
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task

class StoragePlugin(storagesRoutes: StoragesRoutes, filesRoutes: FilesRoutes) extends Plugin {

  /**
    * Optional routes provided by the plugin.
    */
  override def route: Option[Route] = Some(concat(storagesRoutes.routes, filesRoutes.routes))

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = Task.unit
}
