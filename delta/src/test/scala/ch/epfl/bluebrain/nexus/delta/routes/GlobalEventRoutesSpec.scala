package ch.epfl.bluebrain.nexus.delta.routes

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.{`Last-Event-ID`, OAuth2BearerToken}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.delta.config.AppConfig
import ch.epfl.bluebrain.nexus.iam.acls.Acls
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.realms.Realms
import ch.epfl.bluebrain.nexus.kg.resources.Event
import ch.epfl.bluebrain.nexus.kg.routes.EventsSpecBase
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import monix.eval.Task
import ch.epfl.bluebrain.nexus.delta.routes.GlobalEventRoutesSpec._

class GlobalEventRoutesSpec extends EventsSpecBase {

  private val aclsApi = mock[Acls[Task]]
  private val realms  = mock[Realms[Task]]

  private val routes                     = new TestableEventRoutes(events, aclsApi, realms).routes
  private val token: Option[AccessToken] = Some(AccessToken("valid"))
  private val oauthToken                 = OAuth2BearerToken("valid")
  private val prefix                     = appConfig.http.prefix
  aclsApi.hasPermission(Path./, read)(caller) shouldReturn Task.pure(true)
  realms.caller(token.value) shouldReturn Task(caller)

  "GlobalEventRoutes" should {

    "return all events" in {
      Get(s"/$prefix/events") ~> addCredentials(oauthToken) ~> routes ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected)
      }
    }

    "return all events from the last seen" in {
      Get(s"/$prefix/events").addHeader(`Last-Event-ID`(0.toString)) ~> addCredentials(oauthToken) ~> routes ~> check {
        val expected = jsonContentOf("/events/events.json").asArray.value
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual eventStreamFor(expected, 1)
      }
    }
  }
}

object GlobalEventRoutesSpec {

  class TestableEventRoutes(events: List[Event], acls: Acls[Task], realms: Realms[Task])(implicit
      as: ActorSystem,
      config: AppConfig
  ) extends GlobalEventRoutes(acls, realms)(as, config.persistence, config.http) {

    private val envelopes = events.zipWithIndex.map {
      case (ev, idx) =>
        EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, 1L)
    }

    override protected def source(
        tag: String,
        offset: Offset
    ): Source[ServerSentEvent, NotUsed] = {
      val toDrop = offset match {
        case NoOffset    => 0
        case Sequence(v) => v + 1
      }
      Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(eventToSse(ee).toList))
    }
  }
}
