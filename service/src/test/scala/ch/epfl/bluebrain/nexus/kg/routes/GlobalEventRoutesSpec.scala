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
import ch.epfl.bluebrain.nexus.iam.types.Caller
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.routes.GlobalEventRoutesSpec.TestableEventRoutes
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.service.config.AppConfig
import io.circe.Encoder
import monix.eval.Task

class GlobalEventRoutesSpec extends EventsSpecBase {

  private val aclsApi = mock[Acls[Task]]
  private val realms  = mock[Realms[Task]]

  val routes = new TestableEventRoutes(events, aclsApi, realms, caller).routes
  aclsApi.hasPermission(Path./, read)(caller) shouldReturn Task.pure(true)

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

  class TestableEventRoutes(events: List[Event], acls: Acls[Task], realms: Realms[Task], caller: Caller)(implicit
      as: ActorSystem,
      config: AppConfig
  ) extends GlobalEventRoutes(acls, realms, caller) {

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
