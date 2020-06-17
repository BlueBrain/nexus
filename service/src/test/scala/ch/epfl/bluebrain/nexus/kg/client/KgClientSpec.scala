package ch.epfl.bluebrain.nexus.kg.client

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.http.scaladsl.model.headers.Accept
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.commons.test.Resources
import ch.epfl.bluebrain.nexus.commons.test.io.IOOptionValues
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.User
import ch.epfl.bluebrain.nexus.iam.client.IamClientError
import ch.epfl.bluebrain.nexus.kg.client.KgClientError.NotFound
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.resources.{Event, Id, ResourceF, ResourceV}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.Json
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors}

import scala.concurrent.duration._

class KgClientSpec
    extends TestKit(ActorSystem("IamClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfter
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with IOOptionValues
    with Resources
    with Eventually
    with Inspectors
    with TestHelper {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(5.seconds, 15.milliseconds)

  private val config =
    KgClientConfig(url"http://example.com/v1")

  private val resClient: HttpClient[IO, ResourceV] = mock[HttpClient[IO, ResourceV]]
  private val accept                               = Accept(`application/json`.mediaType, `application/ld+json`)

  private val source = mock[EventSource[Event]]
  private val client = new KgClient[IO](config, source, _ => resClient)

  before {
    Mockito.reset(source, resClient)
  }

  trait Ctx {
    // format: off
    val project = Project(genIri, genString(), genString(), None, genIri, genIri, Map.empty, genUUID, genUUID, 1L, false, Instant.EPOCH, genIri, Instant.EPOCH, genIri)
    // format: on
    val id         = url"http://example.com/prefix/myId"
    val resourceId = urlEncode(id)

    val json          = jsonContentOf("/serialization/resource.json")
    private val graph = json.toGraph(id).rightValue

    val model                             = ResourceF(
      Id(project.ref, url"http://example.com/prefix/myId"),
      1L,
      Set(url"https://example.com/vocab/A", url"https://example.com/vocab/B"),
      deprecated = false,
      Map.empty,
      None,
      Instant.parse("2020-01-17T12:45:01.479676Z"),
      Instant.parse("2020-01-17T13:45:01.479676Z"),
      User("john", "bbp"),
      User("brenda", "bbp"),
      Schemas.unconstrainedRef,
      ResourceF.Value(Json.obj(), Json.obj(), graph)
    )
    implicit val token: Option[AuthToken] = None

    val resourceEndpoint =
      s"http://example.com/v1/resources/${project.organizationLabel}/${project.label}/_/$resourceId"
    val eventsEndpoint   = url"http://example.com/v1/resources/${project.organizationLabel}/${project.label}/events"
  }

  "A KgClient" when {

    "fetching a resource" should {

      "succeed" in new Ctx {
        val query = Query("format" -> "expanded")
        resClient(Get(Uri(resourceEndpoint).withQuery(query)).addHeader(accept)) shouldReturn IO.pure(model)
        client.resource(project, id).some shouldEqual model
      }

      "succeed with tag" in new Ctx {
        val query = Query("format" -> "expanded", "tag" -> "myTag")
        resClient(Get(Uri(resourceEndpoint).withQuery(query)).addHeader(accept)) shouldReturn
          IO.pure(model)
        client.resource(project, id, "myTag").some shouldEqual model
      }

      "return None" in new Ctx {
        val query = Query("format" -> "expanded")
        resClient(Get(Uri(resourceEndpoint).withQuery(query)).addHeader(accept)) shouldReturn IO.raiseError(
          NotFound("")
        )
        client.resource(project, id).ioValue shouldEqual None
      }

      "propagate the underlying exception" in new Ctx {
        val query                = Query("format" -> "expanded")
        val exs: List[Exception] = List(
          IamClientError.Unauthorized(""),
          IamClientError.Forbidden(""),
          KgClientError.UnmarshallingError(""),
          KgClientError.UnknownError(StatusCodes.InternalServerError, "")
        )
        forAll(exs) { ex =>
          resClient(Get(Uri(resourceEndpoint).withQuery(query)).addHeader(accept)) shouldReturn IO.raiseError(ex)
          client.resource(project, id).failed[Exception] shouldEqual ex
        }
      }
    }

    "fetching the event stream" should {
      val events                                                                         =
        jsonContentOf("/events/events.json").as[List[Event]].rightValue.map(Sequence(genInt().toLong) -> _)

      def aggregateResult(source: Source[EventEnvelope, NotUsed]): Vector[EventEnvelope] =
        source.runFold(Vector.empty[EventEnvelope])(_ :+ _).futureValue

      def aggregateExpected(source: Source[(Offset, Event), NotUsed]): Vector[EventEnvelope] =
        source
          .runFold(Vector.empty[EventEnvelope]) {
            case (acc, (off, ev)) => acc :+ EventEnvelope(off, ev.id.value.asString, ev.rev, ev, ev.rev)
          }
          .futureValue

      val eventsSource: Source[(Offset, Event), NotUsed] = Source(events)

      "succeed" in new Ctx {
        source(eventsEndpoint, None) shouldReturn eventsSource
        aggregateResult(client.events(project.projectLabel, NoOffset)) shouldEqual
          aggregateExpected(eventsSource)
      }
    }
  }
}
