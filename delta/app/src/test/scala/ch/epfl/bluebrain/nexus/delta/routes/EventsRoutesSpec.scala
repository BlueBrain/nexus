package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import akka.persistence.query.Sequence
import ch.epfl.bluebrain.nexus.delta.kernel.utils.UUIDF
import ch.epfl.bluebrain.nexus.delta.sdk.JsonValue
import ch.epfl.bluebrain.nexus.delta.sdk.Permissions.{events, resources}
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.AclEvent.AclAppended
import ch.epfl.bluebrain.nexus.delta.sdk.model.acls.{Acl, AclAddress, AclEvent}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group, Subject}
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.{AuthToken, Caller}
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.permissions.PermissionsEvent.PermissionsAppended
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.RealmEvent.RealmDeprecated
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Envelope, Event}
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.{AclSetup, IdentitiesDummy, ProjectSetup, SseEventLogDummy}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity
import ch.epfl.bluebrain.nexus.delta.utils.RouteFixtures
import ch.epfl.bluebrain.nexus.testkit._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{CancelAfterFailure, Inspectors, OptionValues}

import java.time.Instant
import java.util.UUID

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

  private val uuid                  = UUID.randomUUID()
  implicit private val uuidF: UUIDF = UUIDF.fixed(uuid)
  private val acls                  = AclSetup.init(Set(resources.write, resources.read, events.read), Set(realm)).accepted

  implicit private val subject: Subject = Identity.Anonymous

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(Map(AuthToken("alice") -> caller))
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val (_, projs) =
    ProjectSetup
      .init(orgsToCreate = List.empty, projectsToCreate = List.empty)
      .accepted

  private val sseEventLog = new SseEventLogDummy(
    List(
      Envelope(
        PermissionsAppended(1, Set(resources.write, resources.read, events.read), Instant.EPOCH, subject),
        Sequence(1),
        "permissions",
        1
      ),
      Envelope(
        AclAppended(
          Acl(AclAddress.Root, Identity.Anonymous -> Set(resources.write, resources.read, events.read)),
          1L,
          Instant.EPOCH,
          subject
        ),
        Sequence(2),
        "acls-/",
        1
      ),
      Envelope(RealmDeprecated(Label.unsafe("realm1"), 2, Instant.EPOCH, subject), Sequence(3), "realms-realm1", 1)
    ),
    {
      case ev: PermissionsEvent => JsonValue(ev).asInstanceOf[JsonValue.Aux[Event]]
      case ev: AclEvent         => JsonValue(ev).asInstanceOf[JsonValue.Aux[Event]]
      case ev: RealmEvent       => JsonValue(ev).asInstanceOf[JsonValue.Aux[Event]]
    }
  )

  private val routes = Route.seal(EventsRoutes(identities, acls, projs, sseEventLog))

  "EventsRoutes" should {

    "fail to get the events stream without events/read permission" in {
      acls.append(Acl(AclAddress.Root, alice -> Set(events.read)), 0L).accepted

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
