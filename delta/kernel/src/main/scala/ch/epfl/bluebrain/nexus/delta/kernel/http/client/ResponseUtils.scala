package ch.epfl.bluebrain.nexus.delta.kernel.http.client

import cats.effect.IO
import io.circe.Json
import org.http4s.circe.*
import org.http4s.{EntityDecoder, Response}

object ResponseUtils {

  def decodeBodyAsJson(response: Response[IO]): IO[Json] =
    EntityDecoder[IO, Json]
      .decode(response, strict = false)
      .rethrowT

}
