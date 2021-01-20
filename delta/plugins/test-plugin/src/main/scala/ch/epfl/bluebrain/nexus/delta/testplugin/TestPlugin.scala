package ch.epfl.bluebrain.nexus.delta.testplugin

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import ch.epfl.bluebrain.nexus.delta.sdk.plugin.Plugin
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global

class TestPlugin(permissions: Permissions) extends Plugin {

  override def route: Option[Route] =
    Some(
      pathPrefix("test-plugin") {
        concat(
          get {
            complete(permissions.fetchPermissionSet.map(ps => s"${ps.mkString(",")}").runToFuture)
          }
        )
      }
    )

  override def stop(): Task[Unit] = Task.pure(println(s"Stopping plugin"))
}
