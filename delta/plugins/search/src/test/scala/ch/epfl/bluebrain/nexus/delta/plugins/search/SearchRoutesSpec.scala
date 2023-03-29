package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchRejection
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.syntax.EncoderOps
import io.circe.{Json, JsonObject}
import monix.bio.IO

class SearchRoutesSpec extends BaseRouteSpec {

  private val search = new Search {
    override def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[SearchRejection, Json] =
      IO.pure(payload.asJson)

    override def query(suite: Label, payload: JsonObject, qp: Uri.Query)(implicit
        caller: Caller
    ): IO[SearchRejection, Json] =
      IO.pure(Json.obj(suite.value -> payload.asJson))
  }

  private val fields = Json.obj("fields" -> true.asJson)

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
      val payload = Json.obj("searchAll" -> true.asJson)
      Post("/v1/search/query", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual payload
      }
    }

    "fetch a result related to a search in a suite" in {
      val searchSuiteName = "public"
      val payload         = Json.obj("searchSuite" -> true.asJson)

      Post(s"/v1/search/query/suite/$searchSuiteName", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual Json.obj(searchSuiteName -> payload)
      }
    }

    "fetch a result related to a search across all project" in {
      Get("/v1/search/config") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual fields
      }
    }
  }

}
