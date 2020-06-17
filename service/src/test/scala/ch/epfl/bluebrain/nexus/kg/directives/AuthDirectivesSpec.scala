package ch.epfl.bluebrain.nexus.kg.directives

import java.time.Instant

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.test.EitherValues
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Anonymous
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.iam.client.{IamClient, IamClientError}
import ch.epfl.bluebrain.nexus.kg.TestHelper
import ch.epfl.bluebrain.nexus.kg.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.kg.config.Settings
import ch.epfl.bluebrain.nexus.kg.directives.AuthDirectives._
import ch.epfl.bluebrain.nexus.kg.marshallers.instances._
import ch.epfl.bluebrain.nexus.kg.routes.Routes
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers

//noinspection NameBooleanParameters
class AuthDirectivesSpec
    extends AnyWordSpecLike
    with Matchers
    with TestHelper
    with EitherValues
    with MacroBasedMatchers
    with IdiomaticMockito
    with BeforeAndAfter
    with ScalatestRouteTest {

  implicit private val hc: HttpConfig = Settings(system).appConfig.http

  implicit private val iamClient: IamClient[Task] = mock[IamClient[Task]]
  implicit private val project: Project           = Project(
    genIri,
    "projectLabel",
    "organizationLabel",
    None,
    genIri,
    genIri,
    Map.empty,
    genUUID,
    genUUID,
    1L,
    false,
    Instant.EPOCH,
    genIri,
    Instant.EPOCH,
    genIri
  )
  private val readWrite                           = Set(Permission.unsafe("read"), Permission.unsafe("write"))
  override val read                               = Permission.unsafe("read")
  override val write                              = Permission.unsafe("write")

  before {
    Mockito.reset(iamClient)
  }

  "The AuthDirectives" should {
    "extract the token" in {
      val expected = "token"
      val route    = extractToken {
        case Some(AuthToken(`expected`)) => complete("")
        case Some(_)                     => fail("Token was not extracted correctly.")
        case None                        => fail("Token was not extracted.")
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
    "pass when required permissions exist" in {
      implicit val acls: AccessControlLists = AccessControlLists(
        Path./ -> ResourceAccessControlList(
          url"http://localhost/",
          1L,
          Set.empty,
          Instant.EPOCH,
          Anonymous,
          Instant.EPOCH,
          Anonymous,
          AccessControlList(Anonymous -> readWrite)
        )
      )
      implicit val caller: Caller           = Caller(Anonymous, Set(Anonymous))
      val route                             = Routes.wrap(hasPermission(read).apply(complete("")))
      Get("/") ~> route ~> check {
        status shouldEqual StatusCodes.OK
      }
    }

    "fail the route" when {
      "there are no permissions" in {
        implicit val acls: AccessControlLists = AccessControlLists()
        implicit val caller: Caller           = Caller(Anonymous, Set(Anonymous))
        val route                             = Routes.wrap(hasPermission(write).apply(complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }
      "the client throws an error for caller acls" in {
        implicit val token: Option[AuthToken] = None
        iamClient.acls(any[Iri.Path], true, true)(any[Option[AuthToken]]) shouldReturn Task.raiseError(
          IamClientError.UnknownError(StatusCodes.InternalServerError, "")
        )
        val route                             = Routes.wrap(extractCallerAcls.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.InternalServerError
        }
      }
      "the client returns Unauthorized for caller acls" in {
        implicit val token: Option[AuthToken] = None
        iamClient.acls(any[Iri.Path], true, true)(any[Option[AuthToken]]) shouldReturn Task.raiseError(
          IamClientError.Unauthorized("")
        )
        val route                             = Routes.wrap(extractCallerAcls.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Unauthorized
        }
      }
      "the client returns Forbidden for caller acls" in {
        implicit val token: Option[AuthToken] = None
        iamClient.acls(any[Iri.Path], true, true)(any[Option[AuthToken]]) shouldReturn Task.raiseError(
          IamClientError.Forbidden("")
        )
        val route                             = Routes.wrap(extractCallerAcls.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }

      "the client throws an error for caller" in {
        implicit val token: Option[AuthToken] = None
        iamClient.identities(any[Option[AuthToken]]) shouldReturn Task.raiseError(
          IamClientError.UnknownError(StatusCodes.InternalServerError, "")
        )
        val route                             = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.InternalServerError
        }
      }
      "the client returns Unauthorized for caller" in {
        implicit val token: Option[AuthToken] = None
        iamClient.identities(any[Option[AuthToken]]) shouldReturn Task.raiseError(IamClientError.Unauthorized(""))
        val route                             = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Unauthorized
        }
      }
      "the client returns Forbidden for caller" in {
        implicit val token: Option[AuthToken] = None
        iamClient.identities(any[Option[AuthToken]]) shouldReturn Task.raiseError(IamClientError.Forbidden(""))
        val route                             = Routes.wrap(extractCaller.apply(_ => complete("")))
        Get("/") ~> route ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }
}
