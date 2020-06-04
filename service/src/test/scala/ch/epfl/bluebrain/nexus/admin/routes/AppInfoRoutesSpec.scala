package ch.epfl.bluebrain.nexus.admin.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.config.AppConfig._
import ch.epfl.bluebrain.nexus.admin.routes.HealthChecker._
import ch.epfl.bluebrain.nexus.commons.http.JsonLdCirceSupport._
import io.circe.Json
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Future

class AppInfoRoutesSpec extends AnyWordSpecLike with Matchers with ScalatestRouteTest with IdiomaticMockito {

  private val cassandra   = mock[CassandraHealthChecker]
  private val cluster     = mock[ClusterHealthChecker]
  private val description = Description("admin")
  private val routes      = AppInfoRoutes(description, cluster, cassandra).routes

  "The AppInfoRoutes" should {

    "return the appropriate service description" in {
      Get("/") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "name"    -> Json.fromString(description.name),
          "version" -> Json.fromString(description.version)
        )
      }
    }

    "return the health status when everything is up" in {
      cluster.check shouldReturn Future.successful(Up)
      cassandra.check shouldReturn Future.successful(Up)
      Get("/health") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj("cluster" -> Json.fromString("up"), "cassandra" -> Json.fromString("up"))
      }
    }

    "return the health status when everything is down" in {
      cluster.check shouldReturn Future.successful(Inaccessible)
      cassandra.check shouldReturn Future.successful(Inaccessible)
      Get("/health") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json] shouldEqual Json.obj(
          "cluster"   -> Json.fromString("inaccessible"),
          "cassandra" -> Json.fromString("inaccessible")
        )
      }
    }
  }
}
