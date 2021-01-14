package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, IOValues}
import org.scalatest.matchers.should.Matchers

class IdentitiesRoutesSpec extends RouteHelpers with Matchers with CirceEq with RouteFixtures with IOValues {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))

  private val acls = AclsDummy(
    PermissionsDummy(Set.empty)
  ).accepted

  private val route = Route.seal(
    handleExceptions(IdentityError.exceptionHandler) {
      IdentitiesRoutes(identities, acls)
    }
  )

  "The identity routes" should {
    "return forbidden" in {
      Get("/v1/identities") ~> addCredentials(OAuth2BearerToken("unknown")) ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return unauthorized" in {
      Get("/v1/identities") ~> addCredentials(BasicHttpCredentials("fail")) ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
      }
    }

    "return anonymous" in {
      Get("/v1/identities") ~> Accept(`*/*`) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(jsonContentOf("/identities/anonymous.json"))
      }
    }

    "return all identities" in {
      Get("/v1/identities") ~> Accept(`*/*`) ~> addCredentials(OAuth2BearerToken("alice")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(jsonContentOf("/identities/alice.json"))
      }
    }
  }
}
