package ch.epfl.bluebrain.nexus.testkit

import io.circe.Json
import io.circe.parser.parse
import monix.bio.IO
import org.fusesource.scalate.TemplateEngine

import scala.annotation.tailrec
import scala.io.{Codec, Source}
import scala.util.Random

trait TestHelpers {

  private val codec: Codec = Codec.UTF8

  private val templateEngine = new TemplateEngine()

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
  final def contentOf(resourcePath: String): String = {
    lazy val fromClass       = Option(getClass.getResourceAsStream(resourcePath))
    lazy val fromClassLoader = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
    val is                   = (fromClass orElse fromClassLoader).getOrElse(
      throw new IllegalArgumentException(s"Unable to load resource '$resourcePath' from classpath.")
    )
    Source.fromInputStream(is)(codec).mkString
  }

  final def ioFromMap[A, B, C](map: Map[A, B], ifAbsent: A => C): A => IO[C, B] =
    (a : A) => IO.fromOption(map.get(a), ifAbsent(a))

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String, attributes: Map[String, Any]): String =
    templateEngine.layout(
      "dummy.template",
      templateEngine.compileMoustache(contentOf(resourcePath)),
      attributes
    )

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
   * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String, attributes: (String, Any)*): String =
    contentOf(resourcePath, attributes.toMap)

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
  final def jsonContentOf(resourcePath: String, attributes: Map[String, Any]): Json =
    parse(contentOf(resourcePath, attributes)).toTry.get

  final def jsonContentOf(resourcePath: String, attributes: (String, Any)*): Json =
    jsonContentOf(resourcePath, attributes.toMap)

}

object TestHelpers extends TestHelpers
