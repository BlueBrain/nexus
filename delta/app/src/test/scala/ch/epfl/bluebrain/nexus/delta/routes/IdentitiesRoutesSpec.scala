package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfExceptionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.utils.{RouteFixtures, RouteHelpers}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.testkit.scalatest.bio.IOValues
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, TestHelpers}
import org.scalatest.matchers.should.Matchers

class IdentitiesRoutesSpec
    extends RouteHelpers
    with Matchers
    with CirceEq
    with RouteFixtures
    with IOValues
    with TestHelpers {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val aclCheck = AclSimpleCheck().accepted

  private val route = Route.seal(
    handleExceptions(RdfExceptionHandler.apply) {
      IdentitiesRoutes(identities, aclCheck)
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
