package ch.epfl.bluebrain.nexus.iam.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.iam.auth.{AccessToken, TokenRejection}
import ch.epfl.bluebrain.nexus.iam.marshallers.instances._
import ch.epfl.bluebrain.nexus.iam.realms._
import ch.epfl.bluebrain.nexus.iam.testsyntax._
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.iam.types.IamError.InvalidAccessToken
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, Authenticated, User}
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.routes.Routes
import ch.epfl.bluebrain.nexus.util.Resources
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation
class IdentitiesRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with IdiomaticMockito {

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(3.seconds, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  private val config        = Settings(system).serviceConfig
  implicit private val http = config.http

  private val realms: Realms[Task] = mock[Realms[Task]]

  before {
    Mockito.reset(realms)
  }

  "The IdentitiesRoutes" should {
    val routes = Routes.wrap(new IdentitiesRoutes(realms).routes)
    "return forbidden" in {
      val err = InvalidAccessToken(TokenRejection.InvalidAccessToken)
      realms.caller(any[AccessToken]) shouldReturn Task.raiseError(err)
      Get("/identities").addCredentials(OAuth2BearerToken("token")) ~> routes ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }
    "return anonymous" in {
      realms.caller(any[AccessToken]) shouldReturn Task.pure(Caller.anonymous)
      Get("/identities") ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual jsonContentOf("/identities/anonymous.json")
      }
    }
    "return all identities" in {
      val user   = User("theuser", "therealm")
      val auth   = Authenticated("therealm")
      val caller = Caller(user, Set(user, Anonymous, auth))
      realms.caller(any[AccessToken]) shouldReturn Task.pure(caller)
      Get("/identities").addCredentials(OAuth2BearerToken("token")) ~> routes ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[Json].sort shouldEqual jsonContentOf("/identities/identities.json")
      }
    }
  }
}
