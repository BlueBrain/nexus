package ch.epfl.bluebrain.nexus.testkit

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import monix.bio.{IO, UIO}
import scala.annotation.tailrec
import scala.util.Random

trait TestHelpers extends ClasspathResourceUtils {

  /**
    * Generates an arbitrary string. Ported from nexus-commons
    *
    * @param length the length of the string to be generated
    * @param pool   the possible values that the string may contain
    * @return a new arbitrary string of the specified ''length'' from the specified pool of ''characters''
    */
  final def genString(length: Int = 16, pool: IndexedSeq[Char] = Vector.range('a', 'z')): String = {
    val size = pool.size

    @tailrec
    def inner(acc: String, remaining: Int): String =
      if (remaining <= 0) acc
      else inner(acc + pool(Random.nextInt(size)), remaining - 1)

    inner("", length)
  }

  /**
    * Convert a map to an function returning an IO
    * @param values (key/value) giving the expected result for the given parameter
    */
  final def ioFromMap[A, B](values: (A, B)*): A => UIO[Option[B]] =
    (a: A) => IO.pure(values.toMap.get(a))

  /**
    * Convert a map to an function returning an IO
    * @param map the map giving the expected result for the given parameter
    * @param ifAbsent which error to return if the parameter can't be found
    */
  final def ioFromMap[A, B, C](map: Map[A, B], ifAbsent: A => C): A => IO[C, B] =
    (a: A) => IO.fromOption(map.get(a), ifAbsent(a))
}

object TestHelpers extends TestHelpers
