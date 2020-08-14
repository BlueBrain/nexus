package ch.epfl.bluebrain.nexus.storage.routes

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.storage.{DeltaIdentitiesClient, DeltaIdentitiesClientError}
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.Identity.Anonymous
import ch.epfl.bluebrain.nexus.storage.DeltaIdentitiesClient.{AccessToken, Caller}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.storage.config.Settings
import ch.epfl.bluebrain.nexus.storage.routes.AuthDirectives._
import ch.epfl.bluebrain.nexus.storage.utils.EitherValues
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

//noinspection NameBooleanParameters
class AuthDirectivesSpec
    extends AnyWordSpecLike
    with Matchers
    with EitherValues
    with MacroBasedMatchers
    with IdiomaticMockito
    with BeforeAndAfter
    with ScalatestRouteTest {

  implicit private val hc: HttpConfig = Settings(system).appConfig.http

  implicit private val deltaIdentities: DeltaIdentitiesClient[Task] = mock[DeltaIdentitiesClient[Task]]

  before {
    Mockito.reset(deltaIdentities)
  }

  "The AuthDirectives" should {

    "extract the token" in {
      val expected = "token"
      val route    = extractToken {
        case Some(AccessToken(`expected`)) => complete("")
        case Some(_)                       => fail("Token was not extracted correctly.")
        case None                          => fail("Token was not extracted.")
      }
      Get("/").addCredentials(OAuth2BearerToken(expected)) ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }
    "extract no token" in {
      val route = extractToken {
        case None        => complete("")
        case t @ Some(_) => fail(s"Extracted unknown token '$t'.")
      }
      Get("/") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "extract the caller" in {
      implicit val token: Option[AccessToken] = None
      deltaIdentities()(any[Option[AccessToken]]) shouldReturn Task(Caller(Anonymous, Set.empty))
      val route                               = Routes.wrap(extractCaller.apply(_ => complete("")))
      Get("/") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "fail the route" when {

      "the client throws an error for caller" in {
        implicit val token: Option[AccessToken] = None
        deltaIdentities()(any[Option[AccessToken]]) shouldReturn
          Task.raiseError(DeltaIdentitiesClientError.IdentitiesServerStatusError(StatusCodes.InternalServerError, ""))
        val route                               = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.InternalServerError
        }
      }
      "the client returns Unauthorized for caller" in {
        implicit val token: Option[AccessToken] = None
        deltaIdentities()(any[Option[AccessToken]]) shouldReturn
          Task.raiseError(DeltaIdentitiesClientError.IdentitiesClientStatusError(StatusCodes.Unauthorized, ""))
        val route                               = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Unauthorized
        }
      }
      "the client returns Forbidden for caller" in {
        implicit val token: Option[AccessToken] = None
        deltaIdentities()(any[Option[AccessToken]]) shouldReturn
          Task.raiseError(DeltaIdentitiesClientError.IdentitiesClientStatusError(StatusCodes.Forbidden, ""))
        val route                               = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }
}
