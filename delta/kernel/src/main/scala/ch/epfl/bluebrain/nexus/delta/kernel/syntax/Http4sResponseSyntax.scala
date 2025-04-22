package ch.epfl.bluebrain.nexus.delta.kernel.syntax

import cats.effect.IO
import org.http4s.Response

trait Http4sResponseSyntax {
  implicit final def http4sResponseSyntax(response: Response[IO]): Http4sResponseOps = new Http4sResponseOps(response)
}

final class Http4sResponseOps(private val response: Response[IO]) extends AnyVal {

  def bodyAsString: IO[String] = response.body.compile.to(Array).map(new String(_))

}
