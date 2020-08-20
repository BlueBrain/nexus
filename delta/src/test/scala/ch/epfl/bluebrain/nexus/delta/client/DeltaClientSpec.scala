package ch.epfl.bluebrain.nexus.delta.client

import java.time.Instant

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.client.RequestBuilding.Get
import akka.http.scaladsl.model.ContentTypes.`application/json`
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{StatusCodes, Uri}
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import cats.effect.IO
import ch.epfl.bluebrain.nexus.admin.projects.{Project, ProjectResource}
import ch.epfl.bluebrain.nexus.admin.types.ResourceF
import ch.epfl.bluebrain.nexus.commons.http.HttpClient
import ch.epfl.bluebrain.nexus.commons.http.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.client.DeltaClientError.NotFound
import ch.epfl.bluebrain.nexus.iam.auth.AccessToken
import ch.epfl.bluebrain.nexus.iam.types.Identity.{Anonymous, User}
import ch.epfl.bluebrain.nexus.kg.KgError.AuthenticationFailed
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import ch.epfl.bluebrain.nexus.kg.resources.Event.JsonLd._
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.{ProjectLabel, ProjectRef}
import ch.epfl.bluebrain.nexus.kg.resources.ResourceF.Value
import ch.epfl.bluebrain.nexus.kg.resources.{Event, Id, ResourceV, ResourceF => KgResourceF}
import ch.epfl.bluebrain.nexus.kg.{urlEncode, TestHelper}
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.util.{IOOptionValues, Resources}
import io.circe.Json
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito, Mockito}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{BeforeAndAfter, Inspectors}

import scala.concurrent.duration._

class DeltaClientSpec
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
    DeltaClientConfig(url"http://example.com/v1")

  private val resClient: HttpClient[IO, ResourceV]           = mock[HttpClient[IO, ResourceV]]
  private val projectClient: HttpClient[IO, ProjectResource] = mock[HttpClient[IO, ProjectResource]]
  private val accept                                         = Accept(`application/json`.mediaType, `application/ld+json`)

  private val source = mock[EventSource[Event]]
  private val client = new DeltaClient[IO](config, projectClient, source, _ => resClient)

  before {
    Mockito.reset(source, resClient, projectClient)
  }

  trait Ctx {
    // format: off
    val project = ResourceF(genIri, genUUID, 1L, deprecated = false, Set.empty, Instant.EPOCH, Anonymous, Instant.EPOCH, Anonymous, Project(genString(), genUUID, genString(), None, Map.empty, genIri, genIri))
    // format: on
    val id         = url"http://example.com/prefix/myId"
    val resourceId = urlEncode(id)

    val json          = jsonContentOf("/serialization/resource.json")
    private val graph = json.toGraph(id).rightValue

    val model = KgResourceF(
      Id(ProjectRef(project.uuid), url"http://example.com/prefix/myId"),
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
      Value(Json.obj(), Json.obj(), graph)
    )

    implicit val token: Option[AccessToken] = None

    val resourceEndpoint =
      s"http://example.com/v1/resources/${project.value.organizationLabel}/${project.value.label}/_/$resourceId"
    val projectEndpoint  =
      s"http://example.com/v1/projects/${project.value.organizationLabel}/${project.value.label}"
    val eventsEndpoint   =
      url"http://example.com/v1/resources/${project.value.organizationLabel}/${project.value.label}/events"
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
          AuthenticationFailed,
          DeltaClientError.UnmarshallingError(""),
          DeltaClientError.UnknownError(StatusCodes.InternalServerError, "")
        )
        forAll(exs) { ex =>
          resClient(Get(Uri(resourceEndpoint).withQuery(query)).addHeader(accept)) shouldReturn IO.raiseError(ex)
          client.resource(project, id).failed[Exception] shouldEqual ex
        }
      }
    }

    "fetching a project" should {

      "succeed" in new Ctx {
        projectClient(Get(Uri(projectEndpoint)).addHeader(accept)) shouldReturn IO.pure(project)
        client.project(project.value.organizationLabel, project.value.label).some shouldEqual project
      }

      "return None" in new Ctx {
        projectClient(Get(Uri(projectEndpoint)).addHeader(accept)) shouldReturn
          IO.raiseError(NotFound(""))
        client.project(project.value.organizationLabel, project.value.label).ioValue shouldEqual None
      }

      "propagate the underlying exception" in new Ctx {
        val exs: List[Exception] = List(
          AuthenticationFailed,
          DeltaClientError.UnmarshallingError(""),
          DeltaClientError.UnknownError(StatusCodes.InternalServerError, "")
        )
        forAll(exs) { ex =>
          projectClient(Get(Uri(projectEndpoint)).addHeader(accept)) shouldReturn IO.raiseError(ex)
          client.project(project.value.organizationLabel, project.value.label).failed[Exception] shouldEqual ex
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
        aggregateResult(
          client.events(ProjectLabel(project.value.organizationLabel, project.value.label), NoOffset)
        ) shouldEqual
          aggregateExpected(eventsSource)
      }
    }
  }
}
