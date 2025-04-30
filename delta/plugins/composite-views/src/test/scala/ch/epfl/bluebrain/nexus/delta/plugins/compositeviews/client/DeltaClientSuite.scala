package ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.RdfHttp4sMediaTypes
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClient.RemoteCheckError
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.client.DeltaClientSuite.token
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.model.CompositeViewSource.RemoteProjectSource
import ch.epfl.bluebrain.nexus.delta.plugins.compositeviews.stream.CompositeBranch
import ch.epfl.bluebrain.nexus.delta.rdf.graph.NQuads
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.iriStringContextSyntax
import ch.epfl.bluebrain.nexus.delta.sdk.auth.{AuthTokenProvider, Credentials}
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectStatistics
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, IriFilter, ProjectRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.{Elem, RemainingElems}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.Elem.SuccessElem
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import fs2.Stream
import io.circe.syntax.EncoderOps
import org.http4s.Method.GET
import org.http4s.ServerSentEvent.EventId
import org.http4s.Uri.Path.Root
import org.http4s.circe.*
import org.http4s.client.{Client, UnexpectedStatus}
import org.http4s.dsl.io.{/, *}
import org.http4s.headers.`Content-Type`
import org.http4s.server.middleware.GZip
import org.http4s.syntax.all.*
import org.http4s.{EventStream, HttpRoutes, MediaType, QueryParamDecoder, ServerSentEvent, Uri}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*

class DeltaClientSuite extends NexusSuite {

  private val nQuads = contentOf("remote/resource.nq")

  private val stats = json"""{
          "@context" : "https://bluebrain.github.io/nexus/contexts/statistics.json",
          "lastProcessedEventDateTime" : "1970-01-01T00:00:00Z",
          "eventsCount" : 10,
          "resourcesCount" : 10
        }"""

  private val remainingElems = json"""{
          "@context" : "https://bluebrain.github.io/nexus/contexts/offset.json",
          "count" : 10,
          "maxInstant" : "1970-01-01T00:00:00Z"
        }"""

  private val project    = ProjectRef.unsafe("org", "proj")
  private val resourceId = iri"https://example.com/testresource"
  private val validTag   = Some(UserTag.unsafe("knowntag"))
  private val unknownTag = Some(UserTag.unsafe("unknowntag"))

  private def elem(i: Int): Elem[Unit] =
    SuccessElem(EntityType("test"), iri"https://bbp.epfl.ch/$i", project, Instant.EPOCH, Offset.at(i.toLong), (), 1)

  private def eventStream: EventStream[IO] = Stream
    .range[IO, Int](0, 10)
    .map { i =>
      ServerSentEvent(Some(elem(i).asJson.noSpaces), Some("Success"), Some(EventId(i.toString)))
    }

  private val jsonldContentType      = `Content-Type`(RdfHttp4sMediaTypes.`application/ld+json`)
  private val nquadsContentType      = `Content-Type`(RdfHttp4sMediaTypes.`application/n-quads`)
  private val eventStreamContentType = `Content-Type`(MediaType.`text/event-stream`)

  implicit val userTagQueryParamDecoder: QueryParamDecoder[UserTag] = QueryParamDecoder[String].map(UserTag.unsafe)

  object UserTagQueryParamMatcher extends OptionalQueryParamDecoderMatcher[UserTag]("tag")

  private val deltaService = GZip(
    HttpRoutes
      .of[IO] {
        case GET -> Root / "v1" / "projects" / "org" / "proj" / "statistics" =>
          Ok(stats, jsonldContentType)
        case GET -> Root / "v1" / "elems" / "org" / "proj" / "remaining"     =>
          Ok(remainingElems, jsonldContentType)
        case GET -> Root / "v1" / "elems" / "org" / "proj" / "continuous"    =>
          Ok(eventStream, eventStreamContentType)
        case HEAD -> Root / "v1" / "elems" / "org" / "proj"                  =>
          Ok()
        case GET -> Root / "v1" / "resources" / "org" / "proj" / "_" / resourceId.toString :? UserTagQueryParamMatcher(
              tag
            ) =>
          tag match {
            case None       => Ok(nQuads, nquadsContentType)
            case `validTag` => Ok(nQuads, nquadsContentType)
            case Some(_)    => NotFound()
          }
        case _                                                               => NotFound()
      }
      .orNotFound
  )

  private val deltaClient = DeltaClient(
    Client.fromHttpApp[IO](deltaService),
    AuthTokenProvider.fixedForTest(token),
    Credentials.Anonymous,
    50.millis
  )

  private val source = RemoteProjectSource(
    iri"http://example.com/remote-project-source",
    UUID.randomUUID(),
    IriFilter.None,
    IriFilter.None,
    None,
    includeDeprecated = false,
    project,
    Uri.unsafeFromString("/v1")
  )

  private val unknownSource = source.copy(project = ProjectRef.unsafe("org", "unknown"))

  test("Getting stats from an existing project should succeed") {
    val expected = ProjectStatistics(10L, 10L, Instant.EPOCH)
    deltaClient.projectStatistics(source).assertEquals(expected)
  }

  test("Getting stats from an unknown project should fail") {
    deltaClient.projectStatistics(unknownSource).intercept[UnexpectedStatus]
  }

  test("Getting remaining information from an existing project should succeed") {
    val expected = RemainingElems(10, Instant.EPOCH)
    deltaClient.remaining(source, Offset.Start).assertEquals(expected)
  }

  test("Getting remaining information from an unknown project should fail") {
    deltaClient.remaining(unknownSource, Offset.Start).intercept[UnexpectedStatus]
  }

  test("Getting elems should succeed") {
    val expected = (0 to 4).map(elem).toList
    val stream   = deltaClient.elems(source, CompositeBranch.Run.Main, Offset.Start)

    stream.take(5).assert(expected)
  }

  test("Getting a resource as n-quads should work") {
    val expected = Some(NQuads(nQuads, resourceId))
    deltaClient.resourceAsNQuads(source, resourceId).assertEquals(expected)
  }

  test("Getting a resource as n-quads should work with tag") {
    val taggedSource = source.copy(resourceTag = validTag)
    val expected     = Some(NQuads(nQuads, resourceId))
    deltaClient.resourceAsNQuads(taggedSource, resourceId).assertEquals(expected)
  }

  test("Getting a resource as n-quads should return none with an unknown tag") {
    val taggedSource = source.copy(resourceTag = unknownTag)
    val expected     = None
    deltaClient.resourceAsNQuads(taggedSource, resourceId).assertEquals(expected)
  }

  test("Checking elems should work") {
    deltaClient.checkElems(source).assert
  }

  test("Checking elems should fail for an invalid source") {
    deltaClient.checkElems(unknownSource).intercept[RemoteCheckError]
  }
}

object DeltaClientSuite {

  private val token = "secretToken"

}
