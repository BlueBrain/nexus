package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.ContentType
import cats.effect.IO
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.directives.FileResponse.{Content, Metadata}
import ch.epfl.bluebrain.nexus.delta.sdk.{AkkaSource, JsonLdValue}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.Response.Complete
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.HttpResponseFields

/**
  * A file response content
  *
  * @param metadata
  *   the file metadata
  * @param content
  *   the file content
  */
final case class FileResponse(metadata: Metadata, content: Content)

object FileResponse {

  type Content = IO[Either[Complete[JsonLdValue], AkkaSource]]

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
  final case class Metadata(filename: String, contentType: ContentType, bytes: Long)

  def apply[E: JsonLdEncoder: HttpResponseFields](
      filename: String,
      contentType: ContentType,
      bytes: Long,
      io: IO[Either[E, AkkaSource]]
  ) =
    new FileResponse(
      Metadata(filename, contentType, bytes),
      io.map { r =>
        r.leftMap { e =>
          Complete(e).map(JsonLdValue(_))
        }
      }
    )

  def apply(filename: String, contentType: ContentType, bytes: Long, source: AkkaSource): FileResponse =
    new FileResponse(Metadata(filename, contentType, bytes), IO.pure(Right(source)))
}
