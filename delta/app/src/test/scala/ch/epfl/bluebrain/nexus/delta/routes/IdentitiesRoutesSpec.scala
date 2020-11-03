package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.error.IdentityError
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.{Anonymous, Authenticated, Group, User}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Label}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclsDummy, IdentitiesDummy, PermissionsDummy, RemoteContextResolutionDummy}
import ch.epfl.bluebrain.nexus.delta.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.testkit.{CirceEq, IOValues, TestHelpers}
import monix.execution.Scheduler
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class IdentitiesRoutesSpec
    extends AnyWordSpecLike
    with ScalatestRouteTest
    with Matchers
    with CirceEq
    with RouteHelpers
    with TestHelpers
    with IOValues {

  implicit private val rcr: RemoteContextResolutionDummy =
    RemoteContextResolutionDummy(
      contexts.resource   -> jsonContentOf("contexts/resource.json"),
      contexts.identities -> jsonContentOf("contexts/identities.json")
    )

  implicit private val ordering: JsonKeyOrdering = JsonKeyOrdering.alphabetical
  implicit private val baseUri: BaseUri          = BaseUri("http://localhost", Label.unsafe("v1"))
  implicit private val s: Scheduler              = Scheduler.global

  private val realm  = Label.unsafe("wonderland")
  private val user   = User("alice", realm)
  private val caller = Caller(user, Set(user, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(
    Map(
      AuthToken("alice") -> caller
    )
  )
  private val acls       = AclsDummy(
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
