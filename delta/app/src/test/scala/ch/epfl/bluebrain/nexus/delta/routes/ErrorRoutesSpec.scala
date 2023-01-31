package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec

class ErrorRoutesSpec extends BaseRouteSpec {

  private lazy val routes = Route.seal(new ErrorRoutes().routes)

  "The error route" should {
    "return the expected error when fetching the invalid error" in {
      Get("/v1/errors/invalid") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }
  }

}
