package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.{ContextValue, RemoteContextResolution}
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.AuthorizationFailed
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfExceptionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Caller.Anonymous
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.User
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection.InvalidAccessToken
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller, TokenRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.Permission
import ch.epfl.bluebrain.nexus.delta.sdk.model.BaseUri
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{Acls, Identities}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.testkit.{IOValues, TestHelpers}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler.Implicits.global
import org.mockito.IdiomaticMockito
import org.scalatest.matchers.should.Matchers

class AuthDirectivesSpec extends RouteHelpers with TestHelpers with Matchers with IdiomaticMockito with IOValues {

  implicit private val cl = getClass.getClassLoader

  implicit val baseUri: BaseUri = BaseUri("http://localhost", Label.unsafe("v1"))

  implicit private val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(contexts.error -> ContextValue.fromFile("contexts/error.json").accepted)

  implicit private val jsonKeys                     =
    JsonKeyOrdering.default(topKeys = List("@context", "@id", "@type", "reason", "details"))

  val user        = User("alice", Label.unsafe("wonderland"))
  val userCaller  = Caller(user, Set(user))
  val user2       = User("bob", Label.unsafe("wonderland"))
  val user2Caller = Caller(user2, Set(user2))

  val identities = new Identities {

    override def exchange(token: AuthToken): IO[TokenRejection, Caller] = {
      token match {
        case AuthToken("alice") => IO.pure(userCaller)
        case AuthToken("bob")   => IO.pure(user2Caller)
        case _                  => IO.raiseError(InvalidAccessToken(Some("Expired JWT")))

      }
    }
  }

  val acls = mock[Acls]

  val directives = new AuthDirectives(identities, acls) {}
  val permission = Permission.unsafe("test/read")

  private val callerRoute: Route =
    handleExceptions(RdfExceptionHandler.apply) {
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
        response.asString shouldEqual "alice"
      }
    }

    "correctly extract Anonymous" in {
      Get("/user") ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asString shouldEqual "anonymous"
      }
    }

    "fail with an invalid token" in {
      Get("/user") ~> addCredentials(OAuth2BearerToken("unknown")) ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
        response.asJson shouldEqual jsonContentOf("identities/invalid-access-token.json")
      }
    }

    "fail with invalid credentials" in {
      Get("/user") ~> addCredentials(BasicHttpCredentials("alice")) ~> callerRoute ~> check {
        response.status shouldEqual StatusCodes.Unauthorized
        response.asJson shouldEqual jsonContentOf("identities/authentication-failed.json")
      }
    }

    "correctly authorize user" in {
      acls.authorizeFor(AclAddress.Root, permission)(userCaller) shouldReturn UIO.pure(true)
      Get("/user") ~> addCredentials(OAuth2BearerToken("alice")) ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.OK
        response.asString shouldEqual "alice"
      }
    }

    "correctly reject Anonymous " in {
      acls.authorizeFor(AclAddress.Root, permission)(Caller.Anonymous) shouldReturn UIO.pure(false)
      Get("/user") ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
    }

    "correctly reject user without permission " in {
      acls.authorizeFor(AclAddress.Root, permission)(user2Caller) shouldReturn UIO.pure(false)
      Get("/user") ~> addCredentials(OAuth2BearerToken("bob")) ~> authorizationRoute ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }
    }
  }
}
