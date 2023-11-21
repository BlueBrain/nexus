package ch.epfl.bluebrain.nexus.testkit

import scala.annotation.tailrec
import scala.util.Random

trait Generators {

  /**
    * Generates an arbitrary string. Ported from nexus-commons
    *
    * @param length
    *   the length of the string to be generated
    * @param pool
    *   the possible values that the string may contain
    * @return
    *   a new arbitrary string of the specified ''length'' from the specified pool of ''characters''
    */
  final def genString(length: Int = 16, pool: IndexedSeq[Char] = Vector.range('a', 'z')): String = {
    val size = pool.size

    @tailrec
    def inner(acc: String, remaining: Int): String =
      if (remaining <= 0) acc
      else inner(acc + pool(Random.nextInt(size)), remaining - 1)

    inner("", length)
  }
}
