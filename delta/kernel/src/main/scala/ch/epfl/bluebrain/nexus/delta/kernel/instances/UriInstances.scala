package ch.epfl.bluebrain.nexus.delta.kernel.instances

import io.circe.{Decoder, Encoder}
import org.http4s.Uri

trait UriInstances {

  implicit final val http4sUriPathDecoder: Decoder[Uri.Path] =
    Decoder.decodeString.map(s => Uri.Path.unsafeFromString(s))
  implicit final val http4sUriPathEncoder: Encoder[Uri.Path] = Encoder.encodeString.contramap(_.toString())

}
