package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.http.scaladsl.server.RouteConcatenation
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.akka.marshalling.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.JsonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.*
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{FileData, SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec
import fs2.Stream

import java.nio.ByteBuffer

class ResponseToJsonLdSpec extends CatsEffectSpec with RouteHelpers with JsonSyntax with RouteConcatenation {

  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context,
      contexts.error             -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )
  implicit val jo: JsonKeyOrdering          = JsonKeyOrdering.default()

  private val FileContents = "hello"

  private def fileSourceOfString(value: String) = Stream.emit(ByteBuffer.wrap(value.getBytes))

  private def responseWith(
      contentType: ContentType,
      data: FileData,
      cacheable: Boolean
  ) = {
    val etag = Option.when(cacheable)("test")
    IO.pure(
      FileResponse[ResourceRejection]("file.name", contentType, etag, Some(1024L), data)
    ).attemptNarrow[ResourceRejection]
  }

  private def responseWithError(error: ResourceRejection) =
    responseWith(
      `text/plain(UTF-8)`,
      Stream.raiseError[IO](error),
      cacheable = false
    )

  private def request = Get() ~> Accept(`*/*`)

  "ResponseToJsonLd file handling" should {

    "return the contents of a file" in {
      val route = responseWith(`text/plain(UTF-8)`, fileSourceOfString(FileContents), cacheable = true)
      request ~> emit(route) ~> check {
        status shouldEqual StatusCodes.OK
        contentType shouldEqual `text/plain(UTF-8)`
        response.asString shouldEqual FileContents
        response.expectConditionalCacheHeaders
      }
    }

    "not return the conditional cache headers" in {
      val route = responseWith(`text/plain(UTF-8)`, fileSourceOfString(FileContents), cacheable = false)
      request ~> emit(route) ~> check {
        response.expectNoConditionalCacheHeaders
      }
    }

    "return an error from a file content IO" ignore {
      val error = ResourceNotFound(nxv + "xxx", ProjectRef.unsafe("org", "proj"))
      val route = responseWithError(error)
      request ~> emit(route) ~> check {
        status shouldEqual StatusCodes.NotFound
        contentType.mediaType shouldEqual `application/ld+json`
        response.asJsonObject.apply("@type").flatMap(_.asString).value shouldEqual "ResourceNotFound"
      }
    }
  }
}
