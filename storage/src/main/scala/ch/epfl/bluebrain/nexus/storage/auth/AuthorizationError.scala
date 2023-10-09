package ch.epfl.bluebrain.nexus.storage.auth

import ch.epfl.bluebrain.nexus.delta.kernel.jwt.TokenRejection

sealed abstract class AuthorizationError(message: String) extends Exception(message) with Product with Serializable {
  override def fillInStackTrace(): AuthorizationError = this
}

object AuthorizationError {

  final case object NoToken                                     extends AuthorizationError("No token has been provided.")
  final case class InvalidToken(tokenRejection: TokenRejection) extends AuthorizationError(tokenRejection.getMessage)
  final case class UnauthorizedUser(issuer: String, subject: String)
      extends AuthorizationError(
        s"User '$subject' from realm '$issuer' wrongfully attempted to perform a call to this service."
      )
  final case class TokenNotVerified(tokenRejection: TokenRejection)
      extends AuthorizationError(tokenRejection.getMessage)

}
