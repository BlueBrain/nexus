package ai.senscience.nexus.delta.testplugin

import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri

class TestPluginRoutes(baseUri: BaseUri) {
  def routes: Route =
    pathPrefix("test-plugin") {
      concat(
        get {
          complete(baseUri.toString)
        }
      )
    }
}
