package ch.epfl.bluebrain.nexus.cli.utils

import _root_.io.circe.Json
import _root_.io.circe.parser.parse

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

object Resources extends Resources
