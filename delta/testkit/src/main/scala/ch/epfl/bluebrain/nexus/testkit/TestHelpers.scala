package ch.epfl.bluebrain.nexus.testkit

import java.io.InputStream

import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceError, ClasspathResourceUtils}
import io.circe.Json
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import scala.annotation.tailrec
import scala.util.Random

trait TestHelpers extends ClasspathResourceUtils {

  implicit private val classLoader = getClass.getClassLoader

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

  /**
    * Loads the content of the argument classpath resource as an [[InputStream]].
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as an [[InputStream]]
    */
  final def streamOf(resourcePath: String)(implicit s: Scheduler = Scheduler.global): InputStream =
    runAcceptOrThrow(ioStreamOf(resourcePath))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(
      resourcePath: String,
      attributes: (String, Any)*
  )(implicit s: Scheduler = Scheduler.global): String =
    runAcceptOrThrow(ioContentOf(resourcePath, attributes: _*))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.  The resulting string is parsed into a json value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a json value
    */
  final def jsonContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  )(implicit s: Scheduler = Scheduler.global): Json =
    runAcceptOrThrow(ioJsonContentOf(resourcePath, attributes: _*))

  private def runAcceptOrThrow[A](io: IO[ClasspathResourceError, A])(implicit s: Scheduler): A =
    io.attempt.runSyncUnsafe() match {
      case Left(value)  => throw new IllegalArgumentException(value.toString)
      case Right(value) => value
    }
}

object TestHelpers extends TestHelpers
