package ch.epfl.bluebrain.nexus.storage.client

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import io.circe.{Decoder, Encoder}

import scala.util.Try

package object types {
  implicit val decUri: Decoder[Uri] =
    Decoder.decodeString.emapTry(s => Try(Uri(s)))

  implicit val encUriPath: Encoder[Path] = Encoder.encodeString.contramap(_.toString())
  implicit val decUriPath: Decoder[Path] = Decoder.decodeString.emapTry(s => Try(Path(s)))
}
