package ch.epfl.bluebrain.nexus.delta.kernel.utils

import io.circe.ParsingFailure

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
  final case class InvalidJson(resourcePath: String, raw: String, failure: ParsingFailure)
      extends ClasspathResourceError(
        s"The resource path '$resourcePath' could not be converted to Json because of failure: $failure.\nResource content is:\n$raw"
      )

  /**
    * A retrieved resource from the classpath is not a Json object
    */
  final case class InvalidJsonObject(resourcePath: String)
      extends ClasspathResourceError(s"The resource path '$resourcePath' could not be converted to Json object")

  /**
    * The resource cannot be found on the classpath
    */
  final case class ResourcePathNotFound(resourcePath: String)
      extends ClasspathResourceError(s"The resource path '$resourcePath' could not be found")

}
