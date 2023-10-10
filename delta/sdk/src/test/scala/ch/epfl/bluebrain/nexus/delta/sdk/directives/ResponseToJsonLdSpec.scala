package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.http.scaladsl.server.RouteConcatenation
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.JsonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.BlankResourceId
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.testkit.ShouldMatchers.convertToAnyShouldWrapper
import ch.epfl.bluebrain.nexus.testkit.TestHelpers.jsonContentOf
import monix.bio.IO
import monix.execution.Scheduler

class ResponseToJsonLdSpec extends RouteHelpers with JsonSyntax with RouteConcatenation {

  implicit val s: Scheduler                 = Scheduler.global
  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context,
      contexts.error             -> jsonContentOf("/contexts/error.json").topContextValueOrEmpty
    )
  implicit val jo: JsonKeyOrdering          = JsonKeyOrdering.default()

  private def responseWithSourceError[E: JsonLdEncoder: HttpResponseFields](error: E) = {
    responseWith(
      `text/plain(UTF-8)`,
      IO.raiseError(error)
    )
  }

  private val expectedBlankIdErrorResponse = jsonContentOf(
    "/directives/blank-id.json"
  )

  private val FileContents = "hello"

  private def fileSourceOfString(value: String) = {
    IO.pure(Source.single(ByteString(value)))
  }

  private def responseWith[E: JsonLdEncoder: HttpResponseFields](
      contentType: ContentType,
      contents: IO[E, AkkaSource]
  ) = {
    IO.pure(
      FileResponse(
        "file.name",
        contentType,
        1024,
        contents
      )
    )
  }

  private def request = {
    Get() ~> Accept(`*/*`)
  }

  "ResponseToJsonLd file handling" should {

    "Return the contents of a file" in {
      request ~> emit(
        responseWith(`text/plain(UTF-8)`, fileSourceOfString(FileContents))
      ) ~> check {
        status shouldEqual StatusCodes.OK
        contentType shouldEqual `text/plain(UTF-8)`
        response.asString shouldEqual FileContents
      }
    }

    "Return an error from a file content IO" in {
      request ~> emit(responseWithSourceError[ResourceRejection](BlankResourceId)) ~> check {
        status shouldEqual StatusCodes.BadRequest // BlankResourceId is supposed to result in BadRequest
        contentType.mediaType shouldEqual `application/ld+json`
        response.asJson shouldEqual expectedBlankIdErrorResponse
      }
    }
  }
}
