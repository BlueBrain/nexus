package ch.epfl.bluebrain.nexus.delta.kernel.http

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.http.circe.*
import io.circe.Json
import org.http4s.{EntityDecoder, Response}

object ResponseUtils {

  def decodeBodyAsJson(response: Response[IO]): IO[Json] =
    EntityDecoder[IO, Json]
      .decode(response, strict = false)
      .rethrowT

}
