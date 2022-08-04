package ch.epfl.bluebrain.nexus.delta.routes

import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaSchemeDirectives
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.identities.model.Caller
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContextDummy
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEventLog.ServerSentEventStream
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.{Anonymous, Authenticated, Group}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.{At, Start}
import fs2.Stream
import monix.bio.IO

import java.util.UUID

class EventsRoutesSpec extends BaseRouteSpec {

  private val uuid = UUID.randomUUID()

  private val projectRef = ProjectRef.unsafe("org", "proj")

  private val aclCheck = AclSimpleCheck().accepted

  implicit private val caller: Caller =
    Caller(alice, Set(alice, Anonymous, Authenticated(realm), Group("group", realm)))

  private val identities = IdentitiesDummy(caller)
  private val asAlice    = addCredentials(OAuth2BearerToken("alice"))

  private val acl      = Label.unsafe("acl")
  private val project  = Label.unsafe("project")
  private val resource = Label.unsafe("resource")

  private val event1 = ServerSentEvent("""{"action":"Add"}""", "Acl", "1")
  private val event2 = ServerSentEvent("""{"action":"Create"}""", "Project", "2")
  private val event3 = ServerSentEvent("""{"action":"Create"}""", "Resource", "3")
  private val event4 = ServerSentEvent("""{"action":"Update"}""", "Project", "4")
  private val event5 = ServerSentEvent("""{"action":"Remove"}""", "Acl", "5")

  private val allEvents = List(event1, event2, event3, event4, event5)

  private val sseEventLog = new SseEventLog {

    override def stream(offset: Offset): ServerSentEventStream = offset match {
      case Start     => Stream.emits(allEvents)
      case At(value) =>
        Stream.emits(allEvents).filter(_.id.exists(_.toLongOption.exists(_ > value)))
    }

    override def streamBy(selector: Label, offset: Offset): ServerSentEventStream =
      stream(offset).filter(_.eventType.exists(_.toLowerCase == selector.value))

    override def stream(org: Label, offset: Offset): IO[OrganizationRejection, ServerSentEventStream] =
      IO.raiseWhen(org != projectRef.organization)(OrganizationNotFound(org)).as(stream(offset))

    override def streamBy(
        selector: Label,
        org: Label,
        offset: Offset
    ): IO[OrganizationRejection, ServerSentEventStream] =
      IO.raiseWhen(org != projectRef.organization)(OrganizationNotFound(org)).as(streamBy(selector, offset))

    override def stream(project: ProjectRef, offset: Offset): IO[ProjectRejection, ServerSentEventStream] =
      IO.raiseWhen(project != projectRef)(ProjectNotFound(project)).as(stream(offset))

    override def streamBy(
        selector: Label,
        project: ProjectRef,
        offset: Offset
    ): IO[ProjectRejection, ServerSentEventStream] =
      IO.raiseWhen(project != projectRef)(ProjectNotFound(project)).as(streamBy(selector, offset))

    override def allSelectors: Set[Label] = Set(acl, project, resource)

    override def scopedSelectors: Set[Label] = Set(project, resource)
  }

  private val routes = Route.seal(
    EventsRoutes(
      identities,
      aclCheck,
      sseEventLog,
      DeltaSchemeDirectives(
        FetchContextDummy.empty,
        ioFromMap(uuid -> projectRef.organization),
        ioFromMap(uuid -> projectRef)
      )
    )
  )

  "EventsRoutes" should {

    "fail to get the events stream without events/read permission" in {
      aclCheck.append(AclAddress.Root, alice -> Set(events.read)).accepted

      Head("/v1/events") ~> routes ~> check {
        response.status shouldEqual StatusCodes.Forbidden
      }

      val endpoints = List(
        "/v1/events",
        "/v1/acl/events",
        "/v1/project/events",
        "/v1/resource/events",
        "/v1/resource/org/events",
        s"/v1/resource/$uuid/events",
        "/v1/resource/org/proj/events",
        s"/v1/resource/$uuid/$uuid/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.Forbidden
          response.asJson shouldEqual jsonContentOf("errors/authorization-failed.json")
        }
      }
    }

    "return a 404 when the selector is unknown" in {
      Get("/v1/xxx/events") ~> `Last-Event-ID`("2") ~> routes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "return a 404 when trying to fetch events by org/proj for a global selector" in {
      val endpoints = List(
        "/v1/acl/org/events",
        s"/v1/acl/$uuid/events",
        "/v1/acl/org/proj/events",
        s"/v1/acl/$uuid/$uuid/events"
      )
      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
        }
      }
    }

    "return a 404 when trying to fetch events for an unknown org/project" in {
      val endpoints = List(
        "/v1/resource/xxx/events",
        "/v1/resource/org/xxx/events",
        "/v1/resource/xxx/proj/events",
        s"/v1/resource/$uuid/xxx/events",
        s"/v1/resource/xxx/$uuid/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> asAlice ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
        }
      }
    }

    "get the events stream for all events" in {
      Get("/v1/events") ~> asAlice ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(5).strip shouldEqual contentOf("/events/eventstream-0-5.txt").strip
      }
    }

    "get the acl events" in {
      Get("/v1/acl/events") ~> asAlice ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(2).strip shouldEqual contentOf("/events/acl-events.txt").strip
      }
    }

    "get the project events by org and by proj" in {
      val endpoints = List(
        "/v1/project/org/events",
        "/v1/project/org/proj/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("3") ~> asAlice ~> routes ~> check {
          mediaType shouldBe MediaTypes.`text/event-stream`
          chunksStream.asString(1).strip shouldEqual contentOf("/events/project-events.txt").strip
        }
      }
    }

    "check access to SSEs" in {
      Head("/v1/events") ~> asAlice ~> routes ~> check {
        response.status shouldEqual StatusCodes.OK
      }
    }

  }

}
