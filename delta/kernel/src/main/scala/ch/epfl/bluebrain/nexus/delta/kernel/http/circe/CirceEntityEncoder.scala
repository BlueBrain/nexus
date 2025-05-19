package ch.epfl.bluebrain.nexus.delta.kernel.http.circe

import cats.effect.IO
import io.circe.Encoder
import org.http4s.EntityEncoder

/**
 * Encoder which allows http4s to convert to json using jsoniter and circe
 * ported from the circe module of http4s
 * @see https://github.com/http4s/http4s
 */
trait CirceEntityEncoder {
  implicit def circeEntityEncoder[A: Encoder]: EntityEncoder[IO, A] =
    jsonEncoderOf[A]
}

object CirceEntityEncoder extends CirceEntityEncoder
