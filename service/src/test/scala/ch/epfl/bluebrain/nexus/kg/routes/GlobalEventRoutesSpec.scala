package ch.epfl.bluebrain.nexus.kg.routes

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.iam.client.types.{AccessControlLists, Caller}
import ch.epfl.bluebrain.nexus.kg.config.AppConfig
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.routes.GlobalEventRoutesSpec.TestableEventRoutes
import io.circe.Encoder

class GlobalEventRoutesSpec extends EventsSpecBase {

  val routes = new TestableEventRoutes(events, acls, caller).routes

  "GlobalEventRoutes" should {

    "return all events for a project" in {
      Get("/") ~> routes ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events for a project from the last seen" in {
      Get("/").addHeader(`Last-Event-ID`(0.toString)) ~> routes ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }
  }
}

object GlobalEventRoutesSpec {

  class TestableEventRoutes(events: List[Event], acls: AccessControlLists, caller: Caller)(implicit
      as: ActorSystem,
      config: AppConfig
  ) extends GlobalEventRoutes(acls, caller) {

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
