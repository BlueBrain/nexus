package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.{ContentType, HttpHeader, StatusCode, StatusCodes}
import akka.stream.scaladsl.Source
import akka.util.ByteString
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse.{Content, Metadata}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields
import ch.epfl.bluebrain.nexus.delta.sdk.stream.StreamConverter
import ch.epfl.bluebrain.nexus.delta.sdk.{FileData, JsonLdValue}

import scala.reflect.ClassTag

/**
  * A file response content
  *
  * @param metadata
  *   the file metadata
  * @param content
  *   the file content
  */
final case class FileResponse private (metadata: Metadata, content: Content)

object FileResponse {

  type AkkaSource = Source[ByteString, Any]
  type Content    = IO[Either[Complete[JsonLdValue], AkkaSource]]

  /**
    * Metadata for the file response
    *
    * @param filename
    *   the filename
    * @param contentType
    *   the file content type
    * @param bytes
    *   the file size
    */
  final case class Metadata(
      filename: String,
      contentType: ContentType,
      etag: Option[String],
      bytes: Option[Long]
  )

  object Metadata {
    implicit def fileResponseMetadataHttpResponseFields: HttpResponseFields[Metadata] =
      new HttpResponseFields[Metadata] {
        override def statusFrom(value: Metadata): StatusCode       = StatusCodes.OK
        override def headersFrom(value: Metadata): Seq[HttpHeader] = Seq.empty

        override def entityTag(value: Metadata): Option[String] = value.etag
      }
  }

  def apply[E <: Throwable: ClassTag: JsonLdEncoder: HttpResponseFields](
      filename: String,
      contentType: ContentType,
      etag: Option[String],
      bytes: Option[Long],
      data: FileData
  ) =
    new FileResponse(
      Metadata(filename, contentType, etag, bytes),
      convertStream(data).attemptNarrow[E].map { r =>
        r.leftMap { e =>
          Complete(e).map(JsonLdValue(_))
        }
      }
    )

  def unsafe(
      filename: String,
      contentType: ContentType,
      etag: Option[String],
      bytes: Option[Long],
      data: FileData
  ): FileResponse =
    new FileResponse(Metadata(filename, contentType, etag, bytes), convertStream(data).map(Right(_)))

  def noCache(filename: String, contentType: ContentType, bytes: Option[Long], source: AkkaSource): FileResponse =
    new FileResponse(Metadata(filename, contentType, None, bytes), IO.pure(Right(source)))

  private def convertStream(data: FileData) =
    IO.delay {
      StreamConverter(data)
        .map(bytes => ByteString(bytes))
    }
}
