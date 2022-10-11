package ch.epfl.bluebrain.nexus.delta.sdk.utils

import cats.data.NonEmptySet

object NonEmptySetImplicits {

  implicit class NonEmptySetTuple2Ops[K, V](nes: NonEmptySet[(K, V)]) {

    /**
      * Converts a NonEmptySet of 2-element tuple into a Map
      */
    def toMap: Map[K, V] = nes.foldLeft(Map.empty[K, V]) { case (m, elem) => m + elem }

  }

}
