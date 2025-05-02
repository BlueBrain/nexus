package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model

import akka.http.scaladsl.model.{ContentType, ContentTypes}
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import org.http4s.MediaType as Http4sMediaType

final case class MediaType(mainType: String, subType: String) {
  def tree: String = s"$mainType/$subType"

  override def toString: String = tree
}

object MediaType {

  val `application/octet-stream`: MediaType = MediaType(Http4sMediaType.application.`octet-stream`)
  val `application/json`: MediaType         = MediaType(Http4sMediaType.application.json)
  val `text/plain`: MediaType               = MediaType(Http4sMediaType.text.plain)

  implicit val mediaTypeEncoder: Encoder[MediaType] = Encoder.encodeString.contramap(_.tree)

  implicit val mediaTypeDecoder: Decoder[MediaType] = Decoder.decodeString.emap(MediaType.parse)

  def apply(mt: Http4sMediaType): MediaType =
    MediaType(mt.mainType, mt.subType)

  def parse(str: String): Either[String, MediaType] =
    Http4sMediaType.parse(str).bimap(_.sanitized, apply)

  def toAkkaContentType(mt: MediaType): ContentType =
    ContentType.parse(mt.tree).getOrElse(ContentTypes.`application/octet-stream`)

}
