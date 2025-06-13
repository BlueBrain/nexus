package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec

class ErrorRoutesSpec extends BaseRouteSpec {

  private lazy val routes = Route.seal(new ErrorRoutes().routes)

  "The error route" should {
    "return the expected error when fetching the invalid error" in {
      Get("/v1/errors/invalid") ~> routes ~> check {
        response.shouldBeForbidden
      }
    }
  }

}
