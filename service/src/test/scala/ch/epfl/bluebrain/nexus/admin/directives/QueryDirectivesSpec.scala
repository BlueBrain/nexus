package ch.epfl.bluebrain.nexus.admin.directives

import java.net.URLEncoder
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, get}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.admin.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.admin.routes.{Routes, SearchParams}
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.commons.search.FromPagination
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.generic.auto._
import org.mockito.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class QueryDirectivesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with ScalaFutures
    with EitherValues
    with IdiomaticMockito {

  private def genIri: AbsoluteIri              = url"http://nexus.example.com/${UUID.randomUUID()}"
  private def encode(url: AbsoluteIri): String = URLEncoder.encode(url.asString, "UTF-8")
  private val appConfig: AppConfig             = Settings(system).appConfig
  private implicit val http: HttpConfig        = appConfig.http

  private def routes(inner: Route): Route =
    Routes.wrap(inner)

  "Query directives" should {
    "handle pagination" in {
      def paginated(from: Int, size: Int) =
        Routes.wrap(
          (get & QueryDirectives.paginated(AppConfig.PaginationConfig(50, 100))) { pagination =>
            pagination shouldEqual FromPagination(from, size)
            complete(StatusCodes.Accepted)
          }
        )

      Get("/") ~> routes(paginated(0, 50)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
      Get("/?size=42") ~> routes(paginated(0, 42)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
      Get("/?from=1") ~> routes(paginated(1, 50)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
      Get("/?from=1&size=42") ~> routes(paginated(1, 42)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
      Get("/?from=1&size=-42") ~> routes(paginated(1, 0)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
      Get("/?from=1&size=500") ~> routes(paginated(1, 100)) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
    }

    "handle query params" in {
      val createdBy = genIri
      val updatedBy = genIri
      val type1     = genIri
      val type2     = genIri
      def projectParams =
        Routes.wrap(
          (get & QueryDirectives.searchParamsProjects) { params =>
            complete(StatusCodes.OK -> params)
          }
        )

      Get("/") ~> routes(projectParams) ~> check {
        responseAs[SearchParams] shouldEqual SearchParams.empty
      }

      Get(
        s"/?rev=1&deprecated=true&label=myLabel&type=${encode(type1)}&type=${encode(type2)}&createdBy=${encode(createdBy)}&updatedBy=${encode(updatedBy)}"
      ) ~> routes(projectParams) ~> check {
        responseAs[SearchParams] shouldEqual
          SearchParams(
            rev = Some(1L),
            deprecated = Some(true),
            projectLabel = Some(Field("myLabel", exactMatch = false)),
            types = Set(type1, type2),
            createdBy = Some(createdBy),
            updatedBy = Some(updatedBy)
          )

      }
    }
  }
}
