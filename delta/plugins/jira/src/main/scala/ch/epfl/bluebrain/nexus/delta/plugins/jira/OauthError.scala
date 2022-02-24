package ch.epfl.bluebrain.nexus.delta.plugins.jira

import ch.epfl.bluebrain.nexus.delta.sdk.error.SDKError

abstract class OauthError(reason: String, details: Option[String] = None) extends SDKError {
  final override def getMessage: String = details.fold(reason)(d => s"$reason\nDetails: $d")
}

object OauthError {

  final case object NoTokenError extends OauthError("No token has been found for the current user.")

  final case object RequestTokenExpected extends OauthError("A request token was expected for the current user.")

  final case object AccessTokenExpected extends OauthError("An access token was expected for the current user.")

}
