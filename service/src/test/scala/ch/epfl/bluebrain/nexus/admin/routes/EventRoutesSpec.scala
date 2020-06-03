package ch.epfl.bluebrain.nexus.admin.routes

import java.time.Instant
import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.`Last-Event-ID`
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.persistence.query.{EventEnvelope, NoOffset, Offset, Sequence}
import akka.stream.scaladsl.Source
import ch.epfl.bluebrain.nexus.admin.config.AppConfig.{HttpConfig, PersistenceConfig}
import ch.epfl.bluebrain.nexus.admin.config.Settings
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationEvent._
import ch.epfl.bluebrain.nexus.admin.projects.ProjectEvent._
import ch.epfl.bluebrain.nexus.admin.routes.EventRoutesSpec.TestableEventRoutes
import ch.epfl.bluebrain.nexus.commons.test.{EitherValues, Resources}
import ch.epfl.bluebrain.nexus.iam.client.IamClient
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.User
import ch.epfl.bluebrain.nexus.iam.client.types.{AuthToken, Permission}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path
import ch.epfl.bluebrain.nexus.rdf.implicits._
import com.typesafe.config.{Config, ConfigFactory}
import io.circe.Json
import monix.eval.Task
import org.mockito.matchers.MacroBasedMatchers
import org.mockito.{IdiomaticMockito, Mockito}
import org.scalatest.{BeforeAndAfter, Inspectors, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

//noinspection TypeAnnotation
class EventRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with BeforeAndAfter
    with MacroBasedMatchers
    with Resources
    with ScalaFutures
    with OptionValues
    with EitherValues
    with Inspectors
    with IdiomaticMockito {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(3.second, 100.milliseconds)

  override def testConfig: Config = ConfigFactory.load("test.conf")

  private val appConfig     = Settings(system).appConfig
  private implicit val http = appConfig.http
  private implicit val pc   = appConfig.persistence
  private implicit val ic   = appConfig.iam

  private implicit val client = mock[IamClient[Task]]

  before {
    Mockito.reset(client)
    client.hasPermission(any[Path], any[Permission])(any[Option[AuthToken]]) shouldReturn Task.pure(true)
  }

  val instant = Instant.EPOCH
  val subject = User("uuid", "myrealm")

  val orgUuid            = UUID.fromString("d8cf3015-1bce-4dda-ba80-80cd4b5281e5")
  val orgLabel           = "thelabel"
  val orgDescription     = Option("the description")
  val projectUuid        = UUID.fromString("94463ac0-3e9b-4261-80f5-e4253956eee2")
  val projectLabel       = "theprojectlabel"
  val projectDescription = "the project description"
  val projectBase        = url"http://localhost:8080/base/"
  val projectVocab       = url"http://localhost:8080/vocab/"
  val apiMappings = Map(
    "nxv" -> url"https://bluebrain.github.io/nexus/vocabulary/",
    "rdf" -> url"http://www.w3.org/1999/02/22-rdf-syntax-ns#"
  )

  val orgEvents = List(
    OrganizationCreated(
      orgUuid,
      orgLabel,
      orgDescription,
      instant,
      subject
    ),
    OrganizationUpdated(
      orgUuid,
      2L,
      orgLabel,
      orgDescription,
      instant,
      subject
    ),
    OrganizationDeprecated(
      orgUuid,
      2L,
      instant,
      subject
    )
  )

  val projectEvents = List(
    ProjectCreated(
      projectUuid,
      projectLabel,
      orgUuid,
      orgLabel,
      Some(projectDescription),
      apiMappings,
      projectBase,
      projectVocab,
      instant,
      subject
    ),
    ProjectUpdated(
      projectUuid,
      projectLabel,
      Some(projectDescription),
      apiMappings,
      projectBase,
      projectVocab,
      2L,
      instant,
      subject
    ),
    ProjectDeprecated(
      projectUuid,
      2L,
      instant,
      subject
    )
  )

  val orgEventsJsons = Vector(
    jsonContentOf("/events/org-created.json"),
    jsonContentOf("/events/org-updated.json"),
    jsonContentOf("/events/org-deprecated.json")
  )

  val projectEventsJsons = Vector(
    jsonContentOf("/events/project-created.json"),
    jsonContentOf("/events/project-updated.json"),
    jsonContentOf("/events/project-deprecated.json")
  )

  def eventStreamFor(jsons: Vector[Json], drop: Int = 0): String =
    jsons.zipWithIndex
      .drop(drop)
      .map {
        case (json, idx) =>
          val data  = json.noSpaces
          val event = json.hcursor.get[String]("@type").rightValue
          val id    = idx
          s"""data:$data
             |event:$event
             |id:$id""".stripMargin
      }
      .mkString("", "\n\n", "\n\n")

  "The EventRoutes" should {
    "return the organization events in the right order" in {
      val routes = new TestableEventRoutes(orgEvents).routes
      forAll(List("/orgs/events", "/orgs/events/")) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(orgEventsJsons)
        }
      }
    }
    "return the project events in the right order" in {
      val routes = new TestableEventRoutes(projectEvents).routes
      forAll(List("/projects/events", "/projects/events/")) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(projectEventsJsons)
        }
      }
    }
    "return all events in the right order" in {
      val routes = new TestableEventRoutes(orgEvents ++ projectEvents).routes
      forAll(List("/events", "/events/")) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(orgEventsJsons ++ projectEventsJsons)
        }
      }
    }
    "return events from the last seen" in {
      val routes = new TestableEventRoutes(orgEvents ++ projectEvents).routes
      forAll(List("/events", "/events/")) { path =>
        Get(path).addHeader(`Last-Event-ID`(1.toString)) ~> routes ~> check {
          status shouldEqual StatusCodes.OK
          responseAs[String] shouldEqual eventStreamFor(orgEventsJsons ++ projectEventsJsons, 2)
        }
      }
    }

    "return Forbidden when requesting the log with no permissions" in {
      Mockito.reset(client)
      client.hasPermission(any[Path], any[Permission])(any[Option[AuthToken]]) shouldReturn Task.pure(false)
      val routes = new TestableEventRoutes(orgEvents ++ projectEvents).routes
      val endpoints = List(
        "/events",
        "/events/",
        "/orgs/events",
        "/orgs/events/",
        "/projects/events",
        "/projects/events/"
      )
      forAll(endpoints) { path =>
        Get(path) ~> routes ~> check {
          status shouldEqual StatusCodes.Forbidden
        }
      }
    }
  }

}

object EventRoutesSpec {

  //noinspection TypeAnnotation
  class TestableEventRoutes(
      events: List[Any]
  )(implicit as: ActorSystem, hc: HttpConfig, pc: PersistenceConfig, ic: IamClientConfig, cl: IamClient[Task])
      extends EventRoutes() {

    override def routes: Route = Routes.wrap(super.routes)

    private val envelopes = events.zipWithIndex.map {
      case (ev, idx) =>
        EventEnvelope(Sequence(idx.toLong), "persistenceid", 1L, ev, Instant.now().toEpochMilli)
    }

    override protected def source(
        tag: String,
        offset: Offset,
        toSse: EventEnvelope => Option[ServerSentEvent]
    ): Source[ServerSentEvent, NotUsed] = {
      val toDrop = offset match {
        case NoOffset    => 0
        case Sequence(v) => v + 1
      }
      Source(envelopes).drop(toDrop).flatMapConcat(ee => Source(toSse(ee).toList))
    }
  }

}
