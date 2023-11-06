package ch.epfl.bluebrain.nexus.testkit

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.{ClasspathResourceError, ClasspathResourceUtils}
import io.circe.{Json, JsonObject}
import monix.bio.{IO => BIO}
import monix.execution.Scheduler

import java.io.InputStream
import scala.annotation.tailrec
import scala.util.Random

trait TestHelpers extends ClasspathResourceUtils {

  implicit private val classLoader: ClassLoader = getClass.getClassLoader

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

  /**
    * Loads the content of the argument classpath resource as an [[InputStream]].
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as an [[InputStream]]
    */
  final def streamOf(resourcePath: String)(implicit s: Scheduler = Scheduler.global): InputStream =
    bioRunAcceptOrThrow(bioStreamOf(resourcePath))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a string
    */
  final def contentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): String =
    runAcceptOrThrow(ioContentOf(resourcePath, attributes: _*))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values. The resulting string is parsed into a json object value.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a json object value
    */
  final def jsonObjectContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): JsonObject =
    runAcceptOrThrow(ioJsonObjectContentOf(resourcePath, attributes: _*))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values. The resulting string is parsed into a json value.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a json value
    */
  final def jsonContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): Json =
    runAcceptOrThrow(ioJsonContentOf(resourcePath, attributes: _*))

  /**
    * Loads the content of the argument classpath resource as a java Properties and transforms it into a Map of key
    * property and property value.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a map of properties
    */
  final def propertiesOf(resourcePath: String): Map[String, String] =
    runAcceptOrThrow(ioPropertiesOf(resourcePath))

  private def bioRunAcceptOrThrow[A](io: BIO[ClasspathResourceError, A])(implicit s: Scheduler): A =
    io.attempt.runSyncUnsafe() match {
      case Left(value)  => throw new IllegalArgumentException(value.toString)
      case Right(value) => value
    }

  private def runAcceptOrThrow[A](io: IO[A]): A =
    io.attempt.unsafeRunSync() match {
      case Left(value)  => throw new IllegalArgumentException(value.toString)
      case Right(value) => value
    }
}

object TestHelpers extends TestHelpers
