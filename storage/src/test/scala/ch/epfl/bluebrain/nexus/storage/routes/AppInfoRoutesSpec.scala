package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import cats.effect.IO
import ch.epfl.bluebrain.nexus.storage.auth.AuthorizationMethod
import ch.epfl.bluebrain.nexus.storage.config.{AppConfig, Settings}
import ch.epfl.bluebrain.nexus.storage.routes.instances._
import ch.epfl.bluebrain.nexus.storage.utils.Resources
import ch.epfl.bluebrain.nexus.storage.{AkkaSource, Storages}
import io.circe.Json
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util.regex.Pattern.quote

class AppInfoRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with IdiomaticMockito
    with Resources {

  "the app info routes" should {

    implicit val config: AppConfig                        = Settings(system).appConfig
    implicit val authorizationMethod: AuthorizationMethod = AuthorizationMethod.Anonymous
    val route: Route                                      = Routes(mock[Storages[IO, AkkaSource]])

    "return application information" in {
      Get("/") ~> route ~> check {
        status shouldEqual OK
        responseAs[Json] shouldEqual
          jsonContentOf("/app-info.json", Map(quote("{version}") -> config.description.version))
      }
    }
  }
}
