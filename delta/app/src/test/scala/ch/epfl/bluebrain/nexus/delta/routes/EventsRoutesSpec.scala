package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ProjectSetup
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

class EventsRoutesSpec
    extends RouteHelpers
    with Matchers
    with CancelAfterFailure
    with CirceLiteral
    with CirceEq
    with IOFixedClock
    with IOValues
    with OptionValues
    with TestMatchers
    with Inspectors
    with RouteFixtures {

  private val aclCheck = AclSimpleCheck().accepted

  implicit private val subject: Subject = Identity.Anonymous

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val (_, projs) =
    ProjectSetup
      .init(orgsToCreate = List.empty, projectsToCreate = List.empty)
      .accepted

  //TODO
  private val routes = Route.seal(EventsRoutes(identities, aclCheck, projs, null))

  "EventsRoutes" should {

    "fail to get the events stream without events/read permission" in {
      aclCheck.append(AclAddress.Root, alice -> Set(events.read)).accepted

      Head("/v1/events") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      Get("/v1/events") ~> `Last-Event-ID`("2") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
        response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
      }
    }
    "get the events stream" in {
      Get("/v1/events") ~> asAlice ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(3).strip shouldEqual contentOf("/events/eventstream-0-2.txt").strip
      }
    }

    "check access to SSEs" in {
      Head("/v1/events") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
    }
  }

}
