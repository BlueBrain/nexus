package ch.epfl.bluebrain.nexus.delta.kernel.http.circe

import cats.effect.IO
import io.circe.Decoder
import org.http4s.EntityDecoder

/**
 * Decoder which allows http4s to convert responses using jsoniter and circe
 * ported from the circe module of http4s
 * @see https://github.com/http4s/http4s
 */
trait CirceEntityDecoder {
  implicit def circeEntityDecoder[A: Decoder]: EntityDecoder[IO, A] = jsonOf[A]
}

object CirceEntityDecoder extends CirceEntityDecoder
