package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, ResourcePathNotFound}
import io.circe.Json
import io.circe.parser.parse
import monix.bio.IO
import org.fusesource.scalate.TemplateEngine

import scala.io.{Codec, Source}

trait ClasspathResourceUtils {

  private val codec: Codec = Codec.UTF8

  private val templateEngine = new TemplateEngine()

  private def resourceAsTextFrom(resourcePath: String): IO[ClasspathResourceError, String] =
    IO.deferAction { _ =>
      lazy val fromClass       = Option(getClass.getResourceAsStream(resourcePath))
      lazy val fromClassLoader = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
      val task                 = IO.fromOption(fromClass orElse fromClassLoader, ResourcePathNotFound(resourcePath))
      task.map(is => Source.fromInputStream(is)(codec).mkString)
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string or a [[ClasspathResourceError]] when the
    *         resource is not found
    */
  final def ioContentOf(resourcePath: String, attributes: (String, Any)*): IO[ClasspathResourceError, String] =
    resourceAsTextFrom(resourcePath).map { text =>
      templateEngine.layout(
        "dummy.template",
        templateEngine.compileMoustache(text),
        attributes.toMap
      )
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.  The resulting string is parsed into a json value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a json value or an [[ClasspathResourceError]]
    *         when the resource is not found or is not a Json
    */
  final def ioJsonContentOf(resourcePath: String, attributes: (String, Any)*): IO[ClasspathResourceError, Json] =
    for {
      text <- ioContentOf(resourcePath, attributes: _*)
      json <- IO.fromEither(parse(text).leftMap(_ => InvalidJson(resourcePath)))
    } yield json
}

object ClasspathResourceUtils extends ClasspathResourceUtils

/**
  * Enumeration of possible errors when retrieving resources from the classpath
  */
sealed abstract class ClasspathResourceError(reason: String) extends Product with Serializable {
  override def toString: String = reason
}

object ClasspathResourceError {

  /**
    * A retrieved resource from the classpath is not a Json
    */
  final case class InvalidJson(resourcePath: String)
      extends ClasspathResourceError(s"The resource path '$resourcePath' could not be converted to Json")

  /**
    * The resource cannot be found on the classpath
    */
  final case class ResourcePathNotFound(resourcePath: String)
      extends ClasspathResourceError(s"The resource path '$resourcePath' could not be found")

}
