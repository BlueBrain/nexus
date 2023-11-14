package ch.epfl.bluebrain.nexus.testkit.scalatest

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceUtils
import ch.epfl.bluebrain.nexus.testkit.ce.CatsRunContext
import ch.epfl.bluebrain.nexus.testkit.mu.ce.{CatsIOValues => MUnitCatsIOValues}
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.{CatsIOValues => ScalaTestCatsIOValues}
import io.circe.{Json, JsonObject}
import munit.{Assertions => MUnitAssertions}
import org.scalatest.{Assertions => ScalaTestAssertions}

trait ExtractValue {

  implicit class ExtractValueOps[A](io: IO[A]) {
    def extract: A = extractValue(io)
  }

  protected def extractValue[A](io: IO[A]): A
}

trait ScalaTestExtractValue extends ExtractValue with ScalaTestCatsIOValues {
  self: ScalaTestAssertions with CatsRunContext =>

  override def extractValue[A](io: IO[A]): A = io.accepted
}

trait MUnitExtractValue extends ExtractValue with MUnitCatsIOValues {
  self: MUnitAssertions with CatsRunContext =>

  override def extractValue[A](io: IO[A]): A = io.accepted
}

trait ClasspathResources extends ExtractValue {

  final def absolutePath(resourcePath: String): String = ClasspathResourceUtils.absolutePath(resourcePath).extract

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
  final def jsonContentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): Json = ClasspathResourceUtils.ioJsonContentOf(resourcePath, attributes: _*).extract

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
  final def contentOf(
      resourcePath: String,
      attributes: (String, Any)*
  ): String =
    ClasspathResourceUtils.ioContentOf(resourcePath, attributes: _*).extract

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
  final def jsonObjectContentOf(resourcePath: String, attributes: (String, Any)*): JsonObject = {
    ClasspathResourceUtils.ioJsonObjectContentOf(resourcePath, attributes: _*).extract
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
  final def propertiesOf(resourcePath: String): Map[String, String] =
    ClasspathResourceUtils.ioPropertiesOf(resourcePath).extract

}
