package ch.epfl.bluebrain.delta.testplugin

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.{Plugin, PluginInfo}
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

class TestPlugin(pluginDef: PluginInfo, permissions: Permissions) extends Plugin {

  /**
    * Optional routes provided by the plugin.
    */
  override def route: Option[Route] =
    Some(
      pathPrefix("test-plugin" / Segment) { seg =>
        concat(
          get {
            println(seg)
            complete(permissions.fetchPermissionSet.map(ps => s"$seg->${ps.mkString(",")}").runToFuture)
          }
        )
      }
    )

  /**
    * Stop the plugin. This should allow the plugin to terminate gracefully.
    */
  override def stop(): Task[Unit] = Task.pure(println(s"Stopping: $pluginDef"))

  override def info: PluginInfo = pluginDef
}
