package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax._
import io.circe.{Json, JsonObject}

class SearchRoutesSpec extends BaseRouteSpec {

  // Dummy implementation of search which just returns the payload
  private val search = new Search {
    override def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[Json] =
      IO.pure(payload.asJson)

    override def query(suite: Label, payload: JsonObject, qp: Uri.Query)(implicit
        caller: Caller
    ): IO[Json] =
      IO.pure(Json.obj(suite.value -> payload.asJson))
  }

  private val fields = Json.obj("fields" := true)

  private lazy val routes = Route.seal(
    new SearchRoutes(
      IdentitiesDummy(),
      AclSimpleCheck().accepted,
      search,
      fields
    ).routes
  )

  "The search route" should {
    "fetch a result related to a search across all projects" in {
      val payload = Json.obj("searchAll" := true)
      Post("/v1/search/query", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual payload
      }
    }

    "fetch a result related to a search in a suite" in {
      val searchSuiteName = "public"
      val payload         = Json.obj("searchSuite" := true)

      Post(s"/v1/search/query/suite/$searchSuiteName", payload.toEntity) ~> routes ~> check {
        val expectedResponse = Json.obj(searchSuiteName -> payload)
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual expectedResponse
      }
    }

    "fetch fields configuration" in {
      Get("/v1/search/config") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual fields
      }
    }
  }

}
