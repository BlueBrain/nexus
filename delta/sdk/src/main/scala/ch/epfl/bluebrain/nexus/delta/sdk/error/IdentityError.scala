package ch.epfl.bluebrain.nexus.delta.sdk.error

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection

/**
  * Top level error type that represents issues related to authentication and identities
  *
  * @param reason a human readable message for why the error occurred
  */
sealed abstract class IdentityError(reason: String) extends SDKError {

  override def getMessage: String = reason
}

object IdentityError {

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends IdentityError("The supplied authentication is invalid.")

  /**
    * Signals an attempt to consume the service without a valid oauth2 bearer token.
    *
    * @param rejection the specific reason why the token is invalid
    */
  final case class InvalidToken(rejection: TokenRejection) extends IdentityError(rejection.reason)

  val exceptionHandler: ExceptionHandler = ExceptionHandler { case AuthenticationFailed | _: InvalidToken =>
    complete(StatusCodes.Unauthorized)
  }
}
