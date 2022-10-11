package ch.epfl.bluebrain.nexus.delta.sdk.utils

import cats.Order
import cats.data.NonEmptySet

object NonEmptySetUtils {

  implicit class NonEmptySetTuple2Ops[A: Order, B: Order](nes: NonEmptySet[(A, B)]) {

    /**
      * Converts a NonEmptySet of 2-element tuple into a Map
      */
    def toMap: Map[A, B] = { for { element <- nes } yield element }.toMap

  }

}
