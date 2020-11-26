package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import monix.bio.IO

trait IOResourceFSyntax {
  implicit final def resourceFSyntax[E, A](io: IO[E, ResourceF[A]]): IOResourceFOps[E, A] = new IOResourceFOps(io)
}
final class IOResourceFOps[E, A](private val io: IO[E, ResourceF[A]]) extends AnyVal {

  /**
    * Map value of [[ResourceF]] wrapped in an [[IO]].
    *
    * @param f  the mapping function
    * @return   a new [[ResourceF]] with value being the result of applying [[f]] to the value of old [[ResourceF]]
    */
  def mapValue[B](f: A => B): IO[E, ResourceF[B]] = io.map(_.map(f))
}
