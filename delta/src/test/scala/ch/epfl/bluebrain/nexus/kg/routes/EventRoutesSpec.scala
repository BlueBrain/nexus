package ch.epfl.bluebrain.nexus.kg.routes

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.iam.types.{Caller, Permission}
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.routes.EventRoutesSpec.TestableEventRoutes
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.rdf.Iri.Path._
import monix.eval.Task

class EventRoutesSpec extends EventsSpecBase {

  private val aclsApi = mock[Acls[Task]]
  private val realms  = mock[Realms[Task]]

  val eventRoutes = new TestableEventRoutes(events, aclsApi, realms, caller)

  "EventRoutes" should {
    val read = Permission.unsafe("resources/read")
    aclsApi.hasPermission("org" / "project", read)(caller) shouldReturn Task.pure(true)
    aclsApi.hasPermission(/ + "org", read)(caller) shouldReturn Task.pure(true)

    "return all events for a project" in {
      Get("/") ~> eventRoutes.projectRoutes(project) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events for a project from the last seen" in {
      Get("/").addHeader(`Last-Event-ID`(0.toString)) ~> eventRoutes.projectRoutes(project) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }

    "return all events for an organization" in {
      Get("/") ~> eventRoutes.organizationRoutes(organization) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events for an organization from the last seen" in {
      Get("/").addHeader(`Last-Event-ID`(0.toString)) ~> eventRoutes.organizationRoutes(organization) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }
  }

}

object EventRoutesSpec {

  class TestableEventRoutes(events: List[Event], acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
      as: ActorSystem,
      config: AppConfig
  ) extends EventRoutes(acls, realms, caller) {

    private val envelopes = events.zipWithIndex.map {
      case (ev, idx) =>
        EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, 1L)
    }

    override protected def source(tag: String, offset: Offset): Source[ServerSentEvent, NotUsed] = {
      val toDrop = offset match {
        case NoOffset    => 0
        case Sequence(v) => v + 1
      }
      Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(eventToSse(ee).toList))
    }
  }
}
