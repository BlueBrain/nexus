package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import cats.Functor
import cats.syntax.functor._
import monix.bio.IO

trait IOFunctorSyntax {
  implicit final def resourceFSyntax[E, A, F[_]: Functor](io: IO[E, F[A]]): IOFunctorOps[E, A, F] = new IOFunctorOps(io)
}

final class IOFunctorOps[E, A, F[_]: Functor](private val io: IO[E, F[A]]) {

  /**
    * Map value of [[F]] wrapped in an [[IO]].
    *
    * @param f  the mapping function
    * @return   a new [[F]] with value being the result of applying [[f]] to the value of old [[F]]
    */
  def mapValue[B](f: A => B): IO[E, F[B]] = io.map(_.map(f))
}
