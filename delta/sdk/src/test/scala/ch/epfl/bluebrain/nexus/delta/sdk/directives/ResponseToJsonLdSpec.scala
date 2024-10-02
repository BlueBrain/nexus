package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentTypes.`text/plain(UTF-8)`
import akka.http.scaladsl.model.MediaRanges.`*/*`
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.{ContentType, StatusCodes}
import akka.http.scaladsl.server.RouteConcatenation
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.RdfMediaTypes.`application/ld+json`
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.rdf.syntax.JsonSyntax
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.ResourceNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.utils.RouteHelpers
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, SimpleRejection, SimpleResource}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsEffectSpec

class ResponseToJsonLdSpec extends CatsEffectSpec with RouteHelpers with JsonSyntax with RouteConcatenation {

  implicit val rcr: RemoteContextResolution =
    RemoteContextResolution.fixed(
      SimpleResource.contextIri  -> SimpleResource.context,
      SimpleRejection.contextIri -> SimpleRejection.context,
      contexts.error             -> jsonContentOf("contexts/error.json").topContextValueOrEmpty
    )
  implicit val jo: JsonKeyOrdering          = JsonKeyOrdering.default()

  private def responseWithSourceError[E: JsonLdEncoder: HttpResponseFields](error: E) = {
    responseWith(
      `text/plain(UTF-8)`,
      IO.pure(Left(error))
    )
  }

  private val FileContents = "hello"

  private def fileSourceOfString(value: String) = {
    IO.pure(Right(Source.single(ByteString(value))))
  }

  private def responseWith[E: JsonLdEncoder: HttpResponseFields](
      contentType: ContentType,
      contents: IO[Either[E, AkkaSource]]
  ) = {
    IO.pure(
      Right(
        FileResponse(
          "file.name",
          contentType,
          Some(1024L),
          contents
        )
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
      val error = ResourceNotFound(nxv + "xxx", ProjectRef.unsafe("org", "proj"))
      request ~> emit(responseWithSourceError[ResourceRejection](error)) ~> check {
        status shouldEqual StatusCodes.NotFound
        contentType.mediaType shouldEqual `application/ld+json`
        response.asJsonObject.apply("@type").flatMap(_.asString).value shouldEqual "ResourceNotFound"

      }
    }
  }
}
