package ch.epfl.bluebrain.nexus.delta.kernel.instances

import akka.http.scaladsl.model.MediaType.NotCompressible
import akka.http.scaladsl.model.{ContentType, MediaType, MediaTypes}
import cats.syntax.all._
import io.circe.{Decoder, Encoder}

import java.util.Locale

trait ContentTypeInstances {
  implicit val contentTypeEncoder: Encoder[ContentType] =
    Encoder.encodeString.contramap(_.value)

  implicit val contentTypeDecoder: Decoder[ContentType] =
    Decoder.decodeString.emap(
      ContentType
        .parse(_)
        .bimap(
          _.mkString("\n"),
          markBinaryAsNonCompressible
        )
    )

  /**
    * When parsing a custom binary media type, it assumes that it is compressible which is traduced by a performance hit
    * when we compress responses
    */
  private def markBinaryAsNonCompressible(contentType: ContentType) =
    contentType match {
      case b: ContentType.Binary if isCustomMediaType(b.mediaType) =>
        ContentType.Binary(b.mediaType.withComp(NotCompressible))
      case other                                                   => other
    }

  private def isCustomMediaType(mediaType: MediaType) =
    MediaTypes
      .getForKey(mediaType.mainType.toLowerCase(Locale.ROOT) -> mediaType.subType.toLowerCase(Locale.ROOT))
      .isEmpty
}
