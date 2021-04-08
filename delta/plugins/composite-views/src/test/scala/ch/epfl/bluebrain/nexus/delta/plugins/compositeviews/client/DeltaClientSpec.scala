package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.OAuth2BearerToken
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.RouteResult
import akka.testkit.TestKit
import ch.epfl.bluebrain.nexus.delta.kernel.Secret
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.{AccessToken, RemoteProjectSource}
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes
import ch.epfl.bluebrain.nexus.delta.sdk.http.{HttpClient, HttpClientConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.model.Label
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectCountsCollection.ProjectCount
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sdk.testkit.ConfigFixtures
import ch.epfl.bluebrain.nexus.testkit.IOValues
import monix.execution.Scheduler
import org.scalatest.{BeforeAndAfterAll, OptionValues}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import java.util.UUID

class DeltaClientSpec
    extends TestKit(ActorSystem("DeltaClientSpec"))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with OptionValues
    with IOValues
    with ConfigFixtures
    with BeforeAndAfterAll {

  var server: Option[Http.ServerBinding] = None

  val token = "secretToken"

  val stats = """{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "value" : 10
        }"""

  override def beforeAll(): Unit = {
    super.beforeAll()
    server = Some(
      Http()
        .newServerAt("localhost", 8080)
        .bindFlow(
          RouteResult.routeToFlow(
            extractCredentials {
              case Some(OAuth2BearerToken(`token`)) =>
                path("v1" / "projects" / "org" / "proj" / "statistics") {
                  complete(StatusCodes.OK, HttpEntity(ContentType(RdfMediaTypes.`application/ld+json`), stats))
                }
              case _                                =>
                complete(StatusCodes.Forbidden)
            }
          )
        )
        .futureValue()
    )
  }

  override def afterAll(): Unit = {
    server.foreach(_.unbind())
    super.afterAll()
  }

  "The delta client" should {
    implicit val sc: Scheduler             = Scheduler.global
    implicit val httpCfg: HttpClientConfig = httpClientConfig
    val deltaClient                        = DeltaClient(HttpClient())

    "retrieve the statistics" in {
      val source: RemoteProjectSource = RemoteProjectSource(
        iri"http://example.com/remote-project-source",
        UUID.randomUUID(),
        Set.empty,
        Set.empty,
        None,
        includeDeprecated = false,
        ProjectRef(Label.unsafe("org"), Label.unsafe("proj")),
        Uri("http://localhost:8080/v1"),
        Some(AccessToken(Secret(token)))
      )

      deltaClient.statistics(source).accepted shouldEqual ProjectCount(10L, Instant.EPOCH)
    }

    "fail if project is unknown" in {
      val source: RemoteProjectSource = RemoteProjectSource(
        iri"http://example.com/remote-project-source",
        UUID.randomUUID(),
        Set.empty,
        Set.empty,
        None,
        includeDeprecated = false,
        ProjectRef(Label.unsafe("org"), Label.unsafe("unknown")),
        Uri("http://localhost:8080/v1"),
        Some(AccessToken(Secret(token)))
      )

      deltaClient.statistics(source).rejected.errorCode.value shouldEqual StatusCodes.NotFound
    }

    "fail if token is invalid" in {
      val source: RemoteProjectSource = RemoteProjectSource(
        iri"http://example.com/remote-project-source",
        UUID.randomUUID(),
        Set.empty,
        Set.empty,
        None,
        includeDeprecated = false,
        ProjectRef(Label.unsafe("org"), Label.unsafe("proj")),
        Uri("http://localhost:8080/v1"),
        Some(AccessToken(Secret("invalid")))
      )

      deltaClient.statistics(source).rejected.errorCode.value shouldEqual StatusCodes.Forbidden
    }
  }

}
