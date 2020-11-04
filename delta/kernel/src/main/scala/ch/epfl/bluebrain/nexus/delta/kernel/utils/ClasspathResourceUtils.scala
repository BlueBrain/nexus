package ch.epfl.bluebrain.nexus.delta.kernel.utils

import scala.io.{Codec, Source}
import io.circe.Json
import io.circe.parser.parse
import org.fusesource.scalate.TemplateEngine

// $COVERAGE-OFF$
trait ClasspathResourceUtils {

  private val codec: Codec = Codec.UTF8

  private val templateEngine = new TemplateEngine()

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
// $COVERAGE-ON$

object ClasspathResourceUtils extends ClasspathResourceUtils
