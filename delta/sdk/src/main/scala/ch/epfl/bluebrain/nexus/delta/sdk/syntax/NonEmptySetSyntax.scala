package ch.epfl.bluebrain.nexus.delta.sdk.syntax

import cats.data.NonEmptySet

trait NonEmptySetSyntax {
  implicit final def nonEmptySetSyntax[A](nes: NonEmptySet[A]): NonEmptySetOps[A] = new NonEmptySetOps[A](nes)
}

final class NonEmptySetOps[A](private val nes: NonEmptySet[A]) extends AnyVal {

  /**
    * Converts a NonEmptySet into a Map using the provided function to create tuples.
    */
  def toMap[K, V](f: A => (K, V)): Map[K, V] =
    nes.foldLeft(Map.empty[K, V]) { case (m, a) => m + f(a) }

}
