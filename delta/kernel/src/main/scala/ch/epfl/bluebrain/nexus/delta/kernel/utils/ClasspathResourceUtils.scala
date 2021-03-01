package ch.epfl.bluebrain.nexus.delta.kernel.utils

import java.io.InputStream
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassPathResourceUtilsStatic.templateEngine
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, ResourcePathNotFound}
import io.circe.Json
import io.circe.parser.parse
import monix.bio.IO
import org.fusesource.scalate.TemplateEngine
import scala.jdk.CollectionConverters._
import java.util.Properties
import scala.io.{Codec, Source}

trait ClasspathResourceUtils {

  /**
    * Loads the content of the argument classpath resource as an [[InputStream]].
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as an [[InputStream]] or a [[ClasspathResourceError]] when the
    *         resource is not found
    */
  def ioStreamOf(resourcePath: String)(implicit classLoader: ClassLoader): IO[ClasspathResourceError, InputStream] =
    IO.deferAction { _ =>
      lazy val fromClass  = Option(getClass.getResourceAsStream(resourcePath))
      val fromClassLoader = Option(classLoader.getResourceAsStream(resourcePath))
      IO.fromOption(fromClass orElse fromClassLoader, ResourcePathNotFound(resourcePath))
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string or a [[ClasspathResourceError]] when the
    *         resource is not found
    */
  final def ioContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  )(implicit classLoader: ClassLoader): IO[ClasspathResourceError, String] =
    resourceAsTextFrom(resourcePath).map {
      case text if attributes.isEmpty => text
      case text                       => templateEngine.layout("dummy.template", templateEngine.compileMoustache(text), attributes.toMap)
    }

  /**
    * Loads the content of the argument classpath resource as a java Properties and transforms it into a Map
    * of key property and property value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a map of properties or a [[ClasspathResourceError]] when the
    *         resource is not found
    */
  final def ioPropertiesOf(resourcePath: String)(implicit
      classLoader: ClassLoader
  ): IO[ClasspathResourceError, Map[String, String]] =
    ioStreamOf(resourcePath).map { is =>
      val props = new Properties()
      props.load(is)
      props.asScala.toMap
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.  The resulting string is parsed into a json value.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a json value or an [[ClasspathResourceError]]
    *         when the resource is not found or is not a Json
    */
  final def ioJsonContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  )(implicit classLoader: ClassLoader): IO[ClasspathResourceError, Json] =
    for {
      text <- ioContentOf(resourcePath, attributes: _*)
      json <- IO.fromEither(parse(text)).mapError(_ => InvalidJson(resourcePath))
    } yield json

  private def resourceAsTextFrom(resourcePath: String)(implicit
      classLoader: ClassLoader
  ): IO[ClasspathResourceError, String] =
    ioStreamOf(resourcePath).map(is => Source.fromInputStream(is)(Codec.UTF8).mkString)
}

object ClassPathResourceUtilsStatic {
  private[utils] val templateEngine = new TemplateEngine(mode = "dev")
}

object ClasspathResourceUtils extends ClasspathResourceUtils

/**
  * Enumeration of possible errors when retrieving resources from the classpath
  */
sealed abstract class ClasspathResourceError(reason: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): ClasspathResourceError = this
  override def getMessage: String                         = reason
  override def toString: String                           = reason
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
