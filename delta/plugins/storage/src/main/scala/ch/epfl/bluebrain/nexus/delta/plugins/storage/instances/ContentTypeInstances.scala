package ch.epfl.bluebrain.nexus.delta.plugins.storage.instances

import akka.http.scaladsl.model.ContentType
import cats.implicits._
import io.circe.{Decoder, Encoder}

trait ContentTypeInstances {
  implicit val contentTypeEncoder: Encoder[ContentType] =
    Encoder.encodeString.contramap(_.value)

  implicit val contentTypeDecoder: Decoder[ContentType] =
    Decoder.decodeString.emap(ContentType.parse(_).leftMap(_.mkString("\n")))
}
