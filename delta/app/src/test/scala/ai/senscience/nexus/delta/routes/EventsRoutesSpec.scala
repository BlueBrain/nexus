package ai.senscience.nexus.delta.routes

import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{MediaTypes, StatusCodes}
import akka.http.scaladsl.server.Route
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.sdk.acls.AclSimpleCheck
import ch.epfl.bluebrain.nexus.delta.sdk.acls.model.AclAddress
import ch.epfl.bluebrain.nexus.delta.sdk.identities.IdentitiesDummy
import ch.epfl.bluebrain.nexus.delta.sdk.organizations.model.OrganizationRejection.OrganizationNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.permissions.Permissions.events
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.sse.{ServerSentEventStream, SseEventLog}
import ch.epfl.bluebrain.nexus.delta.sdk.utils.BaseRouteSpec
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset.{At, Start}
import fs2.Stream

class EventsRoutesSpec extends BaseRouteSpec {

  private val projectRef = ProjectRef.unsafe("org", "proj")

  private val aclCheck = AclSimpleCheck().accepted

  private val identities = IdentitiesDummy.fromUsers(alice)

  private val project   = Label.unsafe("projects")
  private val resources = Label.unsafe("resources")

  private val event1 = ServerSentEvent("""{"action":"Create"}""", "Projects", "1")
  private val event2 = ServerSentEvent("""{"action":"Create"}""", "Resources", "2")
  private val event3 = ServerSentEvent("""{"action":"Update"}""", "Projects", "3")

  private val allEvents = List(event1, event2, event3)

  private val sseEventLog = new SseEventLog {

    def stream(offset: Offset): ServerSentEventStream = offset match {
      case Start     => Stream.emits(allEvents)
      case At(value) =>
        Stream.emits(allEvents).filter(_.id.exists(_.toLongOption.exists(_ > value)))
    }

    override def streamBy(selector: Label, offset: Offset): ServerSentEventStream =
      stream(offset).filter(_.eventType.exists(_.toLowerCase == selector.value))

    override def stream(org: Label, offset: Offset): IO[ServerSentEventStream] =
      IO.raiseWhen(org != projectRef.organization)(OrganizationNotFound(org)).as(stream(offset))

    override def streamBy(
        selector: Label,
        org: Label,
        offset: Offset
    ): IO[ServerSentEventStream] =
      IO.raiseWhen(org != projectRef.organization)(OrganizationNotFound(org)).as(streamBy(selector, offset))

    override def stream(project: ProjectRef, offset: Offset): IO[ServerSentEventStream] =
      IO.raiseWhen(project != projectRef)(ProjectNotFound(project)).as(stream(offset))

    override def streamBy(
        selector: Label,
        project: ProjectRef,
        offset: Offset
    ): IO[ServerSentEventStream] =
      IO.raiseWhen(project != projectRef)(ProjectNotFound(project)).as(streamBy(selector, offset))

    override def selectors: Set[Label] = Set(project, resources)
  }

  private val routes = Route.seal(
    EventsRoutes(
      identities,
      aclCheck,
      sseEventLog
    )
  )

  "EventsRoutes" should {

    "fail to get the events stream without events/read permission" in {
      aclCheck.append(AclAddress.Root, alice -> Set(events.read)).accepted

      val endpoints = List(
        "/v1/projects/events",
        "/v1/resources/events",
        "/v1/resources/org/events",
        "/v1/resources/org/proj/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.shouldBeForbidden
        }
      }
    }

    "return a 404 when the selector is unknown" in {
      Get("/v1/xxx/events") ~> `Last-Event-ID`("2") ~> routes ~> check {
        response.status shouldEqual StatusCodes.NotFound
      }
    }

    "return a 404 when trying to fetch events for an unknown org/project" in {
      val endpoints = List(
        "/v1/resources/xxx/events",
        "/v1/resources/org/xxx/events",
        "/v1/resources/xxx/proj/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> as(alice) ~> `Last-Event-ID`("2") ~> routes ~> check {
          response.status shouldEqual StatusCodes.NotFound
        }
      }
    }

    "get the resource events" in {
      Get("/v1/resources/events") ~> as(alice) ~> routes ~> check {
        mediaType shouldBe MediaTypes.`text/event-stream`
        chunksStream.asString(2).strip shouldEqual contentOf("events/resource-events.txt").strip
      }
    }

    "get the project events by org and by proj" in {
      val endpoints = List(
        "/v1/projects/org/events",
        "/v1/projects/org/proj/events"
      )

      forAll(endpoints) { endpoint =>
        Get(endpoint) ~> `Last-Event-ID`("0") ~> as(alice) ~> routes ~> check {
          mediaType shouldBe MediaTypes.`text/event-stream`
          chunksStream.asString(2).strip shouldEqual contentOf("events/project-events.txt").strip
        }
      }
    }

  }

}
