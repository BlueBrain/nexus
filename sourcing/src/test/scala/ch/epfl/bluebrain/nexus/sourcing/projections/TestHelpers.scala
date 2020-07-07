package ch.epfl.bluebrain.nexus.sourcing.projections

import io.circe.Json
import io.circe.parser.parse

import scala.annotation.tailrec
import scala.io.Source
import scala.util.Random

trait TestHelpers {

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
    * Loads the content of the argument classpath resource as a string.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String): String =
    Source.fromInputStream(getClass.getResourceAsStream(resourcePath)).mkString

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String, replacements: Map[String, String]): String =
    replacements.foldLeft(contentOf(resourcePath)) {
      case (value, (regex, replacement)) => value.replaceAll(regex, replacement)
    }

  /**
    * Loads the content of the argument classpath resource as a json value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a json value
    */
  @SuppressWarnings(Array("TryGet"))
  final def jsonContentOf(resourcePath: String): Json =
    parse(contentOf(resourcePath)).toTry.get

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.  The resulting string is parsed into a json value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a json value
    */
  @SuppressWarnings(Array("TryGet"))
  final def jsonContentOf(resourcePath: String, replacements: Map[String, String]): Json =
    parse(contentOf(resourcePath, replacements)).toTry.get

}

object TestHelpers extends TestHelpers
