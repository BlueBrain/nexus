package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.headers.{BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.generators.AclGen
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclCollection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler.Implicits.global
import org.mockito.IdiomaticMockito
import org.scalatest.BeforeAndAfter
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AuthDirectivesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with ScalaFutures
    with Matchers
    with IdiomaticMockito
    with BeforeAndAfter {

  val user  = User("alice", Label.unsafe("wonderland"))
  val user2 = User("bob", Label.unsafe("wonderland"))

  val identities = new Identities {

    override def exchange(token: AuthToken): IO[TokenRejection, Caller] = {
      token match {
        case AuthToken("alice") =>
          IO.pure(
            Caller(user, Set(user))
          )
        case AuthToken("bob")   =>
          IO.pure(
            Caller(user2, Set(user2))
          )
        case _                  => IO.raiseError(InvalidAccessToken)

      }
    }
  }

  val acls = mock[Acls]

  val directives = new AuthDirectives(identities, acls) {}
  val permission = Permission.unsafe("test/read")

  before {
    acls.fetchWithAncestors(AclAddress.Root) shouldReturn
      UIO.pure(AclCollection(AclGen.resourceFor(Acl(AclAddress.Root, user -> Set(permission)))))
  }

  private def asString(response: HttpResponse) =
    response.entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)(global).futureValue

  private val callerRoute: Route =
    handleExceptions(IdentityError.exceptionHandler) {
      path("user") {
        directives.extractCaller { implicit caller =>
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

  private val authExceptionHandler: ExceptionHandler = ExceptionHandler { case AuthorizationFailed =>
    complete(StatusCodes.Forbidden)
  }

  private val authorizationRoute: Route =
    handleExceptions(authExceptionHandler) {
      path("user") {
        directives.extractCaller { implicit caller =>
          directives.authorizeFor(AclAddress.Root, permission).apply {
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
    }

  "A route" should {

    "correctly extract the user Alice" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("alice")) ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.OK
        asString(response) shouldEqual "alice"
      }
    }

    "correctly extract Anonymous" in {
      Get("/user") ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.OK
        asString(response) shouldEqual "anonymous"
      }
    }

    "fail with an invalid token" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("unknown")) ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
      }
    }

    "fail with invalid credentials" in {
      Get("/user") ~> addCredentials(BasicHttpCredentials("alice")) ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
      }
    }

    "correctly authorize user" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("alice")) ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.OK
        asString(response) shouldEqual "alice"
      }
    }

    "correctly reject Anonymous " in {
      Get("/user") ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "correctly reject user without permission " in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("bob")) ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
    }
  }
}
