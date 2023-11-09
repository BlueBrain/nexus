package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.typed.scaladsl.adapter._
import akka.actor.{typed, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult
import akka.stream.scaladsl.Source
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.sdk.ConfigFixtures
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig, HttpClientError}
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import io.circe.syntax.EncoderOps
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures

import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DeltaClientSpec
    extends TestKit(ActorSystem("DeltaClientSpec"))
    with CatsEffectSpec
    with ScalaFutures
    with ConfigFixtures
    with BeforeAndAfterAll
    with QueryParamsUnmarshalling {

  implicit val typedSystem: typed.ActorSystem[Nothing] = system.toTyped

  var server: Option[Http.ServerBinding] = None

  private val token = "secretToken"

  private val stats = """{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "eventsCount" : 10,
          "resourcesCount" : 10
        }"""

  private val remainingElems = """{
          "@context" : "https://bluebrain.github.io/nexus/contexts/offset.json",
          "count" : 10,
          "maxInstant" : "1970-01-01T00:00:00Z"
        }"""

  implicit val ec: ExecutionContext = ExecutionContext.global
  private val nQuads                = contentOf("remote/resource.nq")
  private val nQuadsEntity          = HttpEntity(ContentType(RdfMediaTypes.`application/n-quads`), nQuads)
  private val resourceId            = iri"https://example.com/testresource"

  private val project    = ProjectRef.unsafe("org", "proj")
  private val validTag   = Some(UserTag.unsafe("knowntag"))
  private val invalidTag = Some(UserTag.unsafe("unknowntag"))

  private def elem(i: Int): Elem[Unit] =
    SuccessElem(
      EntityType("test"),
      iri"https://bbp.epfl.ch/$i",
      Some(project),
      Instant.EPOCH,
      Offset.at(i.toLong),
      (),
      1
    )

  override def beforeAll(): Unit = {
    super.beforeAll()
    server = Some(
      Http()
        .newServerAt("localhost", 8080)
        .bindFlow(
          RouteResult.routeToFlow(
            extractCredentials {
              case Some(OAuth2BearerToken(`token`)) =>
                concat(
                  (get & path("v1" / "projects" / "org" / "proj" / "statistics")) {
                    complete(StatusCodes.OK, HttpEntity(ContentType(RdfMediaTypes.`application/ld+json`), stats))
                  },
                  (get & path("v1" / "elems" / "org" / "proj" / "remaining")) {
                    complete(
                      StatusCodes.OK,
                      HttpEntity(ContentType(RdfMediaTypes.`application/ld+json`), remainingElems)
                    )
                  },
                  (get & path("v1" / "elems" / "org" / "proj" / "continuous")) {
                    complete(
                      StatusCodes.OK,
                      Source.fromIterator(() => Iterator.from(0)).map { i =>
                        ServerSentEvent(elem(i).asJson.noSpaces, "Success", i.toString)
                      }
                    )
                  },
                  (head & path("v1" / "elems" / "org" / "proj")) {
                    complete(StatusCodes.OK)
                  },
                  (pathPrefix(
                    "v1" / "resources" / "org" / "proj" / "_" / resourceId.toString
                  ) & pathEndOrSingleSlash & parameter("tag".as[UserTag].?)) {
                    case None       => complete(StatusCodes.OK, nQuadsEntity)
                    case `validTag` => complete(StatusCodes.OK, nQuadsEntity)
                    case Some(_)    => complete(StatusCodes.NotFound)
                  }
                )
              case _                                => complete(StatusCodes.Forbidden)
            }
          )
        )
        .futureValue
    )
  }

  override def afterAll(): Unit = {
    server.foreach(_.unbind())
    super.afterAll()
  }

  implicit private val httpCfg: HttpClientConfig = httpClientConfig
  private val deltaClient                        =
    DeltaClient(HttpClient(), AuthTokenProvider.fixedForTest(token), Credentials.Anonymous, 1.second)

  private val source = RemoteProjectSource(
    iri"http://example.com/remote-project-source",
    UUID.randomUUID(),
    Set.empty,
    Set.empty,
    None,
    includeDeprecated = false,
    project,
    Uri("http://localhost:8080/v1")
  )

  private val unknownProjectSource = source.copy(project = ProjectRef.unsafe("org", "unknown"))

  "Getting project statistics" should {

    "work" in {
      deltaClient.projectStatistics(source).accepted shouldEqual ProjectStatistics(10L, 10L, Instant.EPOCH)
    }

    "fail if project is unknown" in {
      deltaClient
        .projectStatistics(unknownProjectSource)
        .rejectedWith[HttpClientError]
        .errorCode
        .value shouldEqual StatusCodes.NotFound
    }
  }

  "Getting remaining information" should {

    "work" in {
      deltaClient.remaining(source, Offset.Start).accepted shouldEqual RemainingElems(10, Instant.EPOCH)
    }

    "fail if project is unknown" in {
      deltaClient
        .remaining(unknownProjectSource, Offset.Start)
        .rejectedWith[HttpClientError]
        .errorCode
        .value shouldEqual StatusCodes.NotFound
    }
  }

  "Getting elems" should {
    "work" in {
      val stream   = deltaClient.elems(source, CompositeBranch.Run.Main, Offset.Start)
      val expected = (0 to 4).map(elem)
      stream.take(5).compile.toList.accepted shouldEqual expected
    }
  }

  "Getting resource as nquads" should {
    "work" in {
      deltaClient.resourceAsNQuads(source, resourceId).accepted.value shouldEqual NQuads(nQuads, resourceId)
    }
    "work with tag" in {
      deltaClient.resourceAsNQuads(source.copy(resourceTag = validTag), resourceId).accepted.value shouldEqual NQuads(
        nQuads,
        resourceId
      )
    }

    "return None if tag doesn't exist" in {
      deltaClient.resourceAsNQuads(source.copy(resourceTag = invalidTag), resourceId).accepted shouldEqual None
    }
  }

  "Checking elems" should {
    "work" in {
      deltaClient.checkElems(source).accepted
    }
  }
}
