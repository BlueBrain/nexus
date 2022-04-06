package ch.epfl.bluebrain.nexus.delta.plugins.jira

sealed trait OAuthToken extends Product with Serializable {
  def value: String
}

object OAuthToken {

  final case class RequestToken(value: String) extends OAuthToken

  final case class AccessToken(value: String) extends OAuthToken
}
