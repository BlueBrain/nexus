package ch.epfl.bluebrain.nexus.delta.sdk.error

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.ExceptionHandler
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.TokenRejection

/**
  * Top level error type that represents issues related to authentification and identities
  *
 * @param reason
  */
sealed abstract class IdentityError(reason: String) extends SDKError {

  override def getMessage: String = reason
}

object IdentityError {

  /**
    * Signals that the provided authentication is not valid.
    */
  final case object AuthenticationFailed extends IdentityError("The supplied authentication is invalid.")

  final case class InvalidToken(rejection: TokenRejection) extends IdentityError(rejection.reason)

  val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case AuthenticationFailed =>
      complete(StatusCodes.Forbidden)
    case _: InvalidToken      =>
      complete(StatusCodes.Unauthorized)
  }
}
