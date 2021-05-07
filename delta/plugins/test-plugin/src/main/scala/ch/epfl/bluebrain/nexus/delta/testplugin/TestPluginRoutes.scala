package ch.epfl.bluebrain.nexus.delta.testplugin

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions
import monix.execution.Scheduler

class TestPluginRoutes(permissions: Permissions)(implicit sc: Scheduler) {
  def routes: Route =
    pathPrefix("test-plugin") {
      concat(
        get {
          complete(permissions.fetchPermissionSet.map(ps => s"${ps.mkString(",")}").runToFuture)
        }
      )
    }
}
