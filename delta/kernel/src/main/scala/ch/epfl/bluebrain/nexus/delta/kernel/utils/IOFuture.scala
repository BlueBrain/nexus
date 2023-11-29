package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO

import scala.concurrent.Future

object IOFuture {

  /**
    * Helper to be used when a Future needs to be canceled.
    *
    * Refer to: https://github.com/typelevel/cats-effect/releases/tag/v3.5.0
    */
  def defaultCancelable[A](future: IO[Future[A]]): IO[A] =
    IO.fromFutureCancelable(future.map(f => (f, IO.unit)))

}
