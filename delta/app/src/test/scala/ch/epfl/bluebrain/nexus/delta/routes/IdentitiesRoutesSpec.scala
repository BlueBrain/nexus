package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{Accept, BasicHttpCredentials, OAuth2BearerToken}
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.http.scaladsl.server.Route
import cats.effect.{IO, Ref}
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.RdfExceptionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.provisioning.ProjectProvisioning
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject}

class IdentitiesRoutesSpec extends BaseRouteSpec {

  private val caller = Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)

  private val aclCheck = AclSimpleCheck().accepted

  private val refSubjects = Ref.unsafe[IO, Set[Subject]](Set.empty[Subject])

  private val projectProvisioning: ProjectProvisioning =
    (subject: Identity.Subject) => refSubjects.update(_ + subject)

  private val route = Route.seal(
    handleExceptions(RdfExceptionHandler.apply) {
      IdentitiesRoutes(identities, aclCheck, projectProvisioning)
    }
  )

  "The identity routes" should {
    "return forbidden" in {
      Get("/v1/identities") ~> addCredentials(OAuth2BearerToken("unknown")) ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        refSubjects.get.accepted shouldBe empty
      }
    }

    "return unauthorized" in {
      Get("/v1/identities") ~> addCredentials(BasicHttpCredentials("fail")) ~> route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        refSubjects.get.accepted shouldBe empty
      }
    }

    "return anonymous" in {
      Get("/v1/identities") ~> Accept(`*/*`) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(jsonContentOf("identities/anonymous.json"))
        refSubjects.get.accepted should contain(Anonymous)
      }
    }

    "return all identities" in {
      Get("/v1/identities") ~> Accept(`*/*`) ~> addCredentials(OAuth2BearerToken("alice")) ~> route ~> check {
        status shouldEqual StatusCodes.OK
        response.asJson should equalIgnoreArrayOrder(jsonContentOf("identities/alice.json"))
        refSubjects.get.accepted should contain(alice)
      }
    }
  }
}
