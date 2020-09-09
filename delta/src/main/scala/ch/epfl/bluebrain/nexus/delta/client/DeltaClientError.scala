package ch.epfl.bluebrain.nexus.delta.client

import akka.http.scaladsl.model.StatusCode

import scala.reflect.ClassTag

sealed abstract class DeltaClientError(val message: String) extends Exception {
  override def fillInStackTrace(): DeltaClientError = this
  override val getMessage: String                   = message
}

object DeltaClientError {

  final case class UnmarshallingError[A](reason: String)(implicit A: ClassTag[A])
      extends DeltaClientError(
        s"Unable to parse or decode the response from delta to a '${A.runtimeClass.getSimpleName}' due to '$reason'."
      )

  final case class NotFound(entityAsString: String) extends DeltaClientError("The resource does not exist")

  final case class UnknownError(status: StatusCode, entityAsString: String)
      extends DeltaClientError("The request did not complete successfully.")
}
