package ch.epfl.bluebrain.nexus.cli.utils

import cats.implicits._
import io.circe.{Decoder, Encoder}
import org.http4s.Uri

/**
  * Collection of shared encoders and decoders.
  */
trait Codecs {

  implicit final val uriDecoder: Decoder[Uri] =
    Decoder.decodeString.emap(str => Uri.fromString(str).leftMap(_ => s"Failed to decode string '$str' as Uri"))

  implicit final val uriEncoder: Encoder[Uri] =
    Encoder.encodeString.contramap(_.renderString)

}

object Codecs extends Codecs
