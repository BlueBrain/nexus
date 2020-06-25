package ch.epfl.bluebrain.nexus.kg.routes

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.iam.client.types._
import ch.epfl.bluebrain.nexus.kg.config.KgConfig
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.routes.EventRoutesSpec.TestableEventRoutes
import io.circe.Encoder

class EventRoutesSpec extends EventsSpecBase {

  val eventRoutes = new TestableEventRoutes(events, acls, caller)

  "EventRoutes" should {

    "return all events for a project" in {
      Get("/") ~> eventRoutes.routes(project) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events for a project from the last seen" in {
      Get("/").addHeader(`Last-Event-ID`(0.toString)) ~> eventRoutes.routes(project) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }

    "return all events for an organization" in {
      Get("/") ~> eventRoutes.routes(organization) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events for an organization from the last seen" in {
      Get("/").addHeader(`Last-Event-ID`(0.toString)) ~> eventRoutes.routes(organization) ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }
  }

}

object EventRoutesSpec {

  class TestableEventRoutes(events: List[Event], acls: AccessControlLists, caller: Caller)(implicit
      as: ActorSystem,
      config: KgConfig
  ) extends EventRoutes(acls, caller) {

    private val envelopes = events.zipWithIndex.map {
      case (ev, idx) =>
        EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, 1L)
    }

    override protected def source(
        tag: String,
        offset: Offset
    )(implicit enc: Encoder[Event]): Source[ServerSentEvent, NotUsed] = {
      val toDrop = offset match {
        case NoOffset    => 0
        case Sequence(v) => v + 1
      }
      Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(eventToSse(ee).toList))
    }
  }
}
