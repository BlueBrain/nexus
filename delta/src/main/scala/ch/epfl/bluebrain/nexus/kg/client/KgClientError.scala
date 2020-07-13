package ch.epfl.bluebrain.nexus.kg.client

import akka.http.scaladsl.model.StatusCode

import scala.reflect.ClassTag

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class KgClientError(val message: String) extends Exception {
  override def fillInStackTrace(): KgClientError = this
  override val getMessage: String                = message
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object KgClientError {

  final case class UnmarshallingError[A](reason: String)(implicit A: ClassTag[A])
      extends KgClientError(
        s"Unable to parse or decode the response from Kg to a '${A.runtimeClass.getSimpleName}' due to '$reason'."
      )

  final case class NotFound(entityAsString: String) extends KgClientError("The resource does not exist")

  final case class UnknownError(status: StatusCode, entityAsString: String)
      extends KgClientError("The request did not complete successfully.")
}
