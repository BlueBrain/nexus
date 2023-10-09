package ch.epfl.bluebrain.nexus.delta.kernel.jwt

/**
  * Enumeration of token rejections.
  *
  * @param reason
  *   a descriptive message for reasons why a token is rejected by the system
  */
// $COVERAGE-OFF$
sealed abstract class TokenRejection(reason: String) extends Exception with Product with Serializable {
  override def fillInStackTrace(): Throwable = this
  override def getMessage: String            = reason
}

object TokenRejection {

  /**
    * Rejection for cases where the AccessToken is not a properly formatted signed JWT.
    */
  final case class InvalidAccessTokenFormat(details: String)
      extends TokenRejection(
        s"Access token is invalid; possible causes are: JWT not signed, encoded parts are not properly encoded or each part is not a valid json, details: '$details'"
      )

  /**
    * Rejection for cases where the access token does not contain a subject in the claim set.
    */
  final case object AccessTokenDoesNotContainSubject extends TokenRejection("The token doesn't contain a subject.")

  /**
    * Rejection for cases where the access token does not contain an issuer in the claim set.
    */
  final case object AccessTokenDoesNotContainAnIssuer extends TokenRejection("The token doesn't contain an issuer.")

  /**
    * Rejection for cases where the issuer specified in the access token claim set is unknown; also applies to issuers
    * of deprecated realms.
    */
  final case object UnknownAccessTokenIssuer extends TokenRejection("The issuer referenced in the token was not found.")

  /**
    * Rejection for cases where the access token is invalid according to JWTClaimsVerifier
    */
  final case class InvalidAccessToken(subject: String, issuer: String, details: String)
      extends TokenRejection(s"The provided token is invalid for user '$subject/$issuer' .")

  /**
    * Rejection for cases where we couldn't fetch the groups from the OIDC provider
    */
  final case class GetGroupsFromOidcError(subject: String, issuer: String)
      extends TokenRejection(
        "The token is invalid; possible causes are: the OIDC provider is unreachable."
      )
}
// $COVERAGE-ON$
