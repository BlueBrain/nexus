package ch.epfl.bluebrain.nexus.testkit.ce

import cats.effect.IO

trait IOFromMap {

  /**
    * Convert a map to an function returning an IO
    *
    * @param values
    *   (key/value) giving the expected result for the given parameter
    */
  final def ioFromMap[A, B](values: (A, B)*): A => IO[Option[B]] =
    (a: A) => IO.pure(values.toMap.get(a))

  /**
    * Convert a map to an function returning an IO
    *
    * @param map
    *   the map giving the expected result for the given parameter
    * @param ifAbsent
    *   which error to return if the parameter can't be found
    */
  final def ioFromMap[A, B, C <: Throwable](map: Map[A, B], ifAbsent: A => C): A => IO[B] =
    (a: A) => IO.fromOption(map.get(a))(ifAbsent(a))

}
