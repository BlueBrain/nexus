package ch.epfl.bluebrain.nexus.delta.sdk.generators

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.{GrantType, WellKnown}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral

object WellKnownGen extends CirceLiteral {

  def create(
      issuer: String,
      grantTypes: Set[GrantType] = Set(GrantType.AuthorizationCode, GrantType.Implicit)
  ): (Uri, WellKnown) = {
    val baseUri = s"https://localhost/auth/$issuer/protocol/openid-connect/"
    Uri(baseUri) ->
      WellKnown(
        issuer,
        grantTypes,
        Set(json"""{ "k": "$issuer" }"""),
        Uri(s"${baseUri}auth"),
        Uri(s"${baseUri}token"),
        Uri(s"${baseUri}userinfo"),
        Some(Uri(s"${baseUri}revocation")),
        Some(Uri(s"${baseUri}logout"))
      )
  }
}
