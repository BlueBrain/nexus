package ch.epfl.bluebrain.nexus.admin.directives

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.Error._
import ch.epfl.bluebrain.nexus.admin.{Error, ExpectedException}
import ch.epfl.bluebrain.nexus.iam.acls.{AccessControlList, AccessControlLists, Acls}
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission, ResourceF}
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Group, User}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Settings
import ch.epfl.bluebrain.nexus.service.exceptions.ServiceError
import ch.epfl.bluebrain.nexus.service.marshallers.instances._
import ch.epfl.bluebrain.nexus.service.routes.Routes
import monix.eval.Task
import monix.execution.Scheduler.global
import org.mockito.IdiomaticMockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class AuthDirectivesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with ScalaFutures
    with IdiomaticMockito {
  private val aclsApi    = mock[Acls[Task]]
  private val realmsApi  = mock[Realms[Task]]
  private val directives = new AuthDirectives(aclsApi, realmsApi)(global) {}

  private val config                    = Settings(system).serviceConfig
  implicit private val http: HttpConfig = config.http

  private val cred    = OAuth2BearerToken("token")
  private val token   = AccessToken(cred.token)
  private val subject = User("alice", "realm")
  private val caller  = Caller(subject, Set(Group("nexus", "bbp")))

  private val path       = "org" / "proj"
  private val path2      = "org2" / "proj2"
  private val permission = Permission.unsafe("write")

  private def authorizeOnRoute(path: Path, permission: Permission)(implicit cred: Caller): Route =
    Routes.wrap(
      (get & directives.authorizeOn(path, permission)) {
        complete(StatusCodes.Accepted)
      }
    )

  private def authCaller(caller: Caller): Route =
    Routes.wrap(
      (get & directives.extractCaller) { extCaller =>
        extCaller shouldEqual caller
        complete(StatusCodes.Accepted)
      }
    )

  "Authorization directives" should {

    "return the caller" in {
      realmsApi.caller(token) shouldReturn Task(caller)
      Get("/") ~> addCredentials(cred) ~> authCaller(caller) ~> check {
        status shouldEqual StatusCodes.Accepted
      }

      Get("/") ~> authCaller(Caller.anonymous) ~> check {
        status shouldEqual StatusCodes.Accepted
      }
    }

    "authorize on a path" in {
      aclsApi.list(path, ancestors = true, self = true)(caller) shouldReturn Task.pure(
        AccessControlLists(
          / -> ResourceF(
            url"http://example.com/1",
            1L,
            Set.empty,
            Instant.now(),
            caller.subject,
            Instant.now,
            caller.subject,
            AccessControlList(caller.subject -> Set(permission))
          )
        )
      )

      Get("/") ~> addCredentials(cred) ~> authorizeOnRoute(path, permission)(caller) ~> check {
        status shouldEqual StatusCodes.Accepted
      }

      aclsApi.list(path, ancestors = true, self = true)(Caller.anonymous) shouldReturn Task.pure(
        AccessControlLists(
          path -> ResourceF(
            url"http://example.com/2",
            1L,
            Set.empty,
            Instant.now(),
            caller.subject,
            Instant.now,
            caller.subject,
            AccessControlList.empty
          )
        )
      )

      Get("/") ~> authorizeOnRoute(path, permission)(Caller.anonymous) ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[Error] shouldEqual Error(
          "AuthorizationFailed",
          "The supplied authentication is not authorized to access this resource."
        )
      }

      aclsApi.list(path2, ancestors = true, self = true)(Caller.anonymous) shouldReturn Task.raiseError(
        ExpectedException
      )

      Get("/") ~> authorizeOnRoute(path2, permission)(Caller.anonymous) ~> check {
        status shouldEqual StatusCodes.InternalServerError
        responseAs[Error] shouldEqual Error(
          classNameOf[ServiceError.InternalError.type],
          "The system experienced an unexpected error, please try again later."
        )
      }
    }

    "fail extracting acls when the client throws an error for caller acls" in {
      aclsApi.list("*" / "*", ancestors = true, self = true)(Caller.anonymous) shouldReturn
        Task.raiseError(new RuntimeException())
      val route = Routes.wrap(directives.extractCallerAcls("*" / "*")(Caller.anonymous)(_ => complete("")))
      Get("/") ~> route ~> check {
        status shouldEqual StatusCodes.InternalServerError
      }
    }

    "extract caller acls" in {
      aclsApi.list("*" / "*", ancestors = true, self = true)(Caller.anonymous) shouldReturn Task.pure(
        AccessControlLists.empty
      )
      val route = Routes.wrap(directives.extractCallerAcls("*" / "*")(Caller.anonymous) { acls =>
        acls shouldEqual AccessControlLists.empty
        complete(StatusCodes.Accepted)
      })
      Get("/") ~> route ~> check {
        status shouldEqual StatusCodes.Accepted
      }
    }
  }

}
