package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.Identities
import ch.epfl.bluebrain.nexus.delta.sdk.directives.AuthDirectivesSpec._
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import monix.bio.IO
import monix.execution.Scheduler.Implicits.global
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AuthDirectivesSpec
    extends AuthDirectives(identities)
    with AnyWordSpecLike
    with ScalatestRouteTest
    with ScalaFutures
    with Matchers {

  private def asString(response: HttpResponse) =
    response.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)(global).futureValue

  private val route: Route =
    handleExceptions(IdentityError.exceptionHandler) {
      path("user") {
        extractCaller { implicit caller =>
          get {
            caller match {
              case Anonymous             => complete("anonymous")
              case Caller(user: User, _) => complete(user.subject)
              case _                     => complete("another")
            }

          }
        }
      }
    }

  "A route" should {

    "correctly extract the user Alice" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("alice")) ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        asString(response) shouldEqual "alice"
      }
    }

    "correctly extract Anonymous" in {
      Get("/user") ~> route ~> check {
        response.status shouldEqual StatusCodes.OK
        asString(response) shouldEqual "anonymous"
      }
    }

    "fail with an invalid token" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("bob")) ~> route ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
      }
    }

    "fail with invalid credentials" in {
      Get("/user") ~> addCredentials(BasicHttpCredentials("alice")) ~> route ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

  }

}

object AuthDirectivesSpec {

  val identities = new Identities {

    override def exchange(token: AuthToken): IO[TokenRejection, Caller] = {
      token match {
        case AuthToken("alice") =>
          IO.pure(
            Caller(User("alice", Label.unsafe("wonderland")), Set.empty)
          )
        case _                  => IO.raiseError(InvalidAccessToken)

      }
    }
  }

}
