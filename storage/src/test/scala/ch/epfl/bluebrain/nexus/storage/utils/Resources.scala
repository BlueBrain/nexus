package ch.epfl.bluebrain.nexus.storage.utils

import io.circe.Json
import io.circe.parser.parse

import scala.io.Source

/**
  * Utility trait that facilitates operating on classpath resources.
  */
trait Resources {

  /**
    * Loads the content of the argument classpath resource as a string.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String): String = {
    val fromClass       = Option(getClass.getResourceAsStream(resourcePath))
    val fromClassLoader = Option(getClass.getClassLoader.getResourceAsStream(resourcePath))
    val is              = (fromClass orElse fromClassLoader).getOrElse(
      throw new IllegalArgumentException(s"Unable to load resource '$resourcePath' from classpath.")
    )
    Source.fromInputStream(is).mkString
  }

  /**
    * Loads the content of the argument classpath resource as a string and replaces all the key matches of
    * the ''replacements'' with their values.
    *
    * @param resourcePath the path of a resource available on the classpath
    * @return the content of the referenced resource as a string
    */
  final def contentOf(resourcePath: String, replacements: Map[String, String]): String =
    replacements.foldLeft(contentOf(resourcePath)) { case (value, (regex, replacement)) =>
      value.replaceAll(regex, replacement)
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

object Resources extends Resources
