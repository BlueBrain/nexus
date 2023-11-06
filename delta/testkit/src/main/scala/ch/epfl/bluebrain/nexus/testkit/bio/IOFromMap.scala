package ch.epfl.bluebrain.nexus.testkit.bio

import monix.bio.{IO, UIO}

trait IOFromMap {

  /**
    * Convert a map to an function returning an IO
    *
    * @param values
    *   (key/value) giving the expected result for the given parameter
    */
  final def ioFromMap[A, B](values: (A, B)*): A => UIO[Option[B]] =
    (a: A) => IO.pure(values.toMap.get(a))

  /**
    * Convert a map to an function returning an IO
    *
    * @param map
    *   the map giving the expected result for the given parameter
    * @param ifAbsent
    *   which error to return if the parameter can't be found
    */
  final def ioFromMap[A, B, C](map: Map[A, B], ifAbsent: A => C): A => IO[C, B] =
    (a: A) => IO.fromOption(map.get(a), ifAbsent(a))

}
