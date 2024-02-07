package ch.epfl.bluebrain.nexus.delta.plugins.search

import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.plugins.search.model.SearchRejection.UnknownSuite
import ch.epfl.bluebrain.nexus.delta.plugins.search.SuiteMatchers._
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.scalatest.matchers.{HavePropertyMatchResult, HavePropertyMatcher}

class SearchRoutesSpec extends BaseRouteSpec {

  private val unknownSuite = UnknownSuite(Label.unsafe("xxx"))

  // Dummy implementation of search which just returns the payload
  private val search = new Search {
    override def query(payload: JsonObject, qp: Uri.Query)(implicit caller: Caller): IO[Json] = {
      IO.raiseWhen(payload.isEmpty)(unknownSuite).as(payload.asJson)
    }

    override def query(suite: Label, payload: JsonObject, qp: Uri.Query)(implicit
        caller: Caller
    ): IO[Json] =
      IO.raiseWhen(payload.isEmpty)(unknownSuite).as(Json.obj(suite.value -> payload.asJson))
  }

  private val fields = Json.obj("fields" := true)

  private val publicProjects = Set(ProjectRef.unsafe("org", "project"), ProjectRef.unsafe("org2", "project2"))
  private val suites         = Map(
    Label.unsafe("public")  -> publicProjects,
    Label.unsafe("private") -> Set(ProjectRef.unsafe("org3", "project3"))
  )

  private lazy val routes = Route.seal(
    new SearchRoutes(
      IdentitiesDummy(),
      AclSimpleCheck().accepted,
      search,
      fields,
      suites
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

    "fail for an invalid payload during a search across all projects" in {
      val payload = Json.obj()
      Post("/v1/search/query", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
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

    "fail for an invalid payload during a search in a suite" in {
      val searchSuiteName = "public"
      val payload         = Json.obj()
      Post(s"/v1/search/query/suite/$searchSuiteName", payload.toEntity) ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
      }
    }

    "fetch fields configuration" in {
      Get("/v1/search/config") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson shouldEqual fields
      }
    }

    "fetch a suite" in {
      Get(s"/v1/search/suites/public") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should have(name("public"))
        response.asJson should have(projects(publicProjects))
      }
    }

    "fetching a unknown suite" in {
      Get(s"/v1/search/suites/unknown") ~> routes ~> check {
        status shouldEqual StatusCodes.NotFound
        response.asJson shouldEqual
          json"""
             {
               "@context" : "https://bluebrain.github.io/nexus/contexts/error.json",
               "@type" : "UnknownSuite",
               "reason" : "The suite 'unknown' can't be found."
             }
              """
      }
    }
  }

}

object SuiteMatchers {
  def name(expectedName: String) = HavePropertyMatcher[Json, String] { json =>
    val actualId = json.hcursor.get[String]("name").toOption
    HavePropertyMatchResult(
      actualId.contains(expectedName),
      "name",
      expectedName,
      actualId.orNull
    )
  }

  def projects(expected: Set[ProjectRef]) = HavePropertyMatcher[Json, Set[ProjectRef]] { json =>
    val actualId = json.hcursor.get[Set[ProjectRef]]("projects").toOption
    HavePropertyMatchResult(
      actualId.contains(expected),
      "name",
      expected,
      actualId.orNull
    )
  }
}
