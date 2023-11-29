package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO

import scala.concurrent.Future

object IOUtils {

  /**
    * Without using fromFutureCancelable, it results in the stream not terminating. Occurred in the migration from
    * cats-effect 2 to 3. Seems wrong but it works.
    */
  def fromFutureLegacy[A](future: IO[Future[A]]): IO[A] =
    IO.fromFutureCancelable(future.map(f => (f, IO.unit)))

}
