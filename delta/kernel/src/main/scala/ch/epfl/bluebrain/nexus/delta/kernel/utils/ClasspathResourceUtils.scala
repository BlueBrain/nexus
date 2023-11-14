package ch.epfl.bluebrain.nexus.delta.kernel.utils

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClassPathResourceUtilsStatic.handleBars
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, InvalidJsonObject, ResourcePathNotFound}
import com.github.jknack.handlebars.{EscapingStrategy, Handlebars}
import io.circe.parser.parse
import io.circe.{Json, JsonObject}

import java.io.InputStream
import java.util.Properties
import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters._

trait ClasspathResourceUtils {

  private val classLoader: ClassLoader = getClass.getClassLoader

  final def absolutePath(resourcePath: String): IO[String] =
    IO.fromOption(Option(getClass.getResource(resourcePath)) orElse Option(classLoader.getResource(resourcePath)))(
      ResourcePathNotFound(resourcePath)
    ).map(_.getPath)

  /**
    * Loads the content of the argument classpath resource as an [[InputStream]].
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as an [[InputStream]] or a [[ClasspathResourceError]] when the resource
    *   is not found
    */
  def ioStreamOf(resourcePath: String): IO[InputStream] =
    IO.defer {
      lazy val fromClass  = Option(getClass.getResourceAsStream(resourcePath))
      val fromClassLoader = Option(classLoader.getResourceAsStream(resourcePath))
      IO.fromOption(fromClass orElse fromClassLoader)(ResourcePathNotFound(resourcePath))
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a string or a [[ClasspathResourceError]] when the resource is not
    *   found
    */
  final def ioContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): IO[String] =
    resourceAsTextFrom(resourcePath).map {
      case text if attributes.isEmpty => text
      case text                       => handleBars.compileInline(text).apply(attributes.toMap.asJava)
    }

  /**
    * Loads the content of the argument classpath resource as a java Properties and transforms it into a Map of key
    * property and property value.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a map of properties or a [[ClasspathResourceError]] when the resource
    *   is not found
    */
  final def ioPropertiesOf(resourcePath: String): IO[Map[String, String]] =
    ioStreamOf(resourcePath).map { is =>
      val props = new Properties()
      props.load(is)
      props.asScala.toMap
    }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values. The resulting string is parsed into a json value.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a json value or an [[ClasspathResourceError]] when the resource is not
    *   found or is not a Json
    */
  final def ioJsonContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): IO[Json] =
    for {
      text <- ioContentOf(resourcePath, attributes: _*)
      json <- IO.fromEither(parse(text).left.map(InvalidJson(resourcePath, text, _)))
    } yield json

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of the
    * ''replacements'' with their values. The resulting string is parsed into a json object.
    *
    * @param resourcePath
    *   the path of a resource available on the classpath
    * @return
    *   the content of the referenced resource as a json value or an [[ClasspathResourceError]] when the resource is not
    *   found or is not a Json
    */
  final def ioJsonObjectContentOf(resourcePath: String, attributes: (String, Any)*): IO[JsonObject] =
    for {
      json    <- ioJsonContentOf(resourcePath, attributes: _*)
      jsonObj <- IO.fromOption(json.asObject)(InvalidJsonObject(resourcePath))
    } yield jsonObj

  private def resourceAsTextFrom(resourcePath: String): IO[String] =
    ioStreamOf(resourcePath).map(is => Source.fromInputStream(is)(Codec.UTF8).mkString)
}

object ClassPathResourceUtilsStatic {
  private[utils] val handleBars = new Handlebars().`with`(EscapingStrategy.NOOP)
}

object ClasspathResourceUtils extends ClasspathResourceUtils
