package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.{StatusCode, StatusCodes}

/**
  * Enumeration of possible Iam identities Client errors.
  */
@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
sealed abstract class IamIdentitiesClientError(val msg: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): IamIdentitiesClientError = this
  override def getMessage: String                           = msg
}

@SuppressWarnings(Array("IncorrectlyNamedExceptions"))
object IamIdentitiesClientError {

  final def unsafe(status: StatusCode, body: String): IamIdentitiesClientError =
    status match {
      case _ if status.isSuccess()       =>
        throw new IllegalArgumentException(s"Successful status code '$status' found, error expected.")
      case code: StatusCodes.ClientError => IdentitiesClientStatusError(code, body)
      case code: StatusCodes.ServerError => IdentitiesServerStatusError(code, body)
      case _                             => IdentitiesUnexpectedStatusError(status, body)
    }

  /**
    * A serialization error when attempting to cast response.
    */
  final case class IdentitiesSerializationError(message: String)
      extends IamIdentitiesClientError(
        s"an IAM request to the identities endpoint could not be converted to 'Caller' type. Details '$message'"
      )

  /**
    * A Client status error (HTTP status codes 4xx).
    */
  final case class IdentitiesClientStatusError(code: StatusCodes.ClientError, message: String)
      extends IamIdentitiesClientError(
        s"an IAM request to the identities endpoint that should have been successful, returned the HTTP status code '$code'. Details '$message'"
      )

  /**
    * A server status error (HTTP status codes 5xx).
    */
  final case class IdentitiesServerStatusError(code: StatusCodes.ServerError, message: String)
      extends IamIdentitiesClientError(
        s"an IAM request to the identities endpoint that should have been successful, returned the HTTP status code '$code'. Details '$message'"
      )

  /**
    * Some other response error which is not 4xx nor 5xx
    */
  final case class IdentitiesUnexpectedStatusError(code: StatusCode, message: String)
      extends IamIdentitiesClientError(
        s"an IAM request to the identities endpoint that should have been successful, returned the HTTP status code '$code'. Details '$message'"
      )

}
