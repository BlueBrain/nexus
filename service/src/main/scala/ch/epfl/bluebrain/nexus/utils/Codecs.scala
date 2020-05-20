package ch.epfl.bluebrain.nexus.utils

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import cats.implicits._
import ch.epfl.bluebrain.nexus.AbsoluteUri
import io.circe.{Decoder, Encoder}
import pureconfig.ConfigConvert
import pureconfig.error.CannotConvert

import scala.util.Try

/**
  * Collection of shared encoders and decoders.
  */
trait Codecs {

  implicit final val uriDecoder: Decoder[Uri] =
    Decoder.decodeString.emap(str => AbsoluteUri(str).leftMap(_ => s"Failed to decode string '$str' as Uri"))

  implicit final val uriEncoder: Encoder[Uri] =
    Encoder.encodeString.contramap(_.toString)

  implicit final val pathDecoder: Decoder[Path] =
    Decoder.decodeString.emap(str => Try(Path(str)).toEither.leftMap(_ => s"Failed to decode string '$str' as Path"))

  implicit final val pathEncoder: Encoder[Path] =
    Encoder.encodeString.contramap(_.toString)

  implicit final val pathConfigConvert: ConfigConvert[Path] =
    ConfigConvert.viaString(
      str => Try(Path(str)).toEither.leftMap(err => CannotConvert(str, classOf[Path].getSimpleName, err.getMessage)),
      _.toString
    )

  implicit final val uriConfigConvert: ConfigConvert[Uri] =
    ConfigConvert.viaString(
      str => AbsoluteUri(str).leftMap(err => CannotConvert(str, classOf[Uri].getSimpleName, err)),
      _.toString
    )

}

object Codecs extends Codecs
