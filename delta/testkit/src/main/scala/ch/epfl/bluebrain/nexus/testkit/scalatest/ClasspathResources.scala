package ch.epfl.bluebrain.nexus.testkit.scalatest

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceLoader
import ch.epfl.bluebrain.nexus.testkit.mu.ce.CatsIOValues as MUnitCatsIOValues
import ch.epfl.bluebrain.nexus.testkit.scalatest.ce.CatsIOValues as ScalaTestCatsIOValues
import io.circe.{Json, JsonObject}
import munit.Assertions as MUnitAssertions
import org.scalatest.Assertions as ScalaTestAssertions

trait ExtractValue {

  implicit class ExtractValueOps[A](io: IO[A]) {
    def extract: A = extractValue(io)
  }

  protected def extractValue[A](io: IO[A]): A
}

trait ScalaTestExtractValue extends ExtractValue with ScalaTestCatsIOValues {
  self: ScalaTestAssertions =>

  override def extractValue[A](io: IO[A]): A = io.accepted
}

trait MUnitExtractValue extends ExtractValue with MUnitCatsIOValues {
  self: MUnitAssertions =>

  override def extractValue[A](io: IO[A]): A = io.accepted
}

trait ClasspathLoader {
  implicit protected val loader: ClasspathResourceLoader = ClasspathResourceLoader()
}

trait ClasspathResources extends ClasspathLoader with ExtractValue {

  final def absolutePath(resourcePath: String): String = loader.absolutePath(resourcePath).extract

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
  ): Json = loader.jsonContentOf(resourcePath, attributes*).extract

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
    loader.contentOf(resourcePath, attributes*).extract

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
    loader.jsonObjectContentOf(resourcePath, attributes*).extract
  }

}
