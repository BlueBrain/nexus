package ch.epfl.bluebrain.nexus.admin.directives

import java.net.URLEncoder
import java.util.UUID

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{complete, get}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams
import ch.epfl.bluebrain.nexus.admin.routes.SearchParams.Field
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.routes.Routes
import ch.epfl.bluebrain.nexus.util.EitherValues
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
  private val config                           = Settings(system).appConfig
  implicit private val http: HttpConfig        = config.http

  private def routes(inner: Route): Route =
    Routes.wrap(inner)

  "Query directives" should {

    "handle query params" in {
      val createdBy     = genIri
      val updatedBy     = genIri
      val type1         = genIri
      val type2         = genIri
      def projectParams =
        Routes.wrap(
          (get & QueryDirectives.searchParamsProjects) { params => complete(StatusCodes.OK -> params) }
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
