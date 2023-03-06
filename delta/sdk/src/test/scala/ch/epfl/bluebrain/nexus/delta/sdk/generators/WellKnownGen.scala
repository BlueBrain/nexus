package ch.epfl.bluebrain.nexus.delta.sdk.generators

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{GrantType, WellKnown}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral

object WellKnownGen extends CirceLiteral {

  def create(
      issuer: String,
      grantTypes: Set[GrantType] = Set(GrantType.AuthorizationCode, GrantType.Implicit)
  ): (Uri, WellKnown) = {
    val baseUri = s"https://localhost/auth/$issuer/protocol/openid-connect/"
    Uri(baseUri) -> createFromUri(baseUri, issuer, grantTypes)
  }

  def createFromUri(
      openIdConfig: Uri,
      issuer: String,
      grantTypes: Set[GrantType] = Set(GrantType.AuthorizationCode, GrantType.Implicit)
  ): WellKnown =
    model.WellKnown(
      issuer,
      grantTypes,
      Set(json"""{ "k": "$issuer" }"""),
      Uri(s"${openIdConfig}auth"),
      Uri(s"${openIdConfig}token"),
      Uri(s"${openIdConfig}userinfo"),
      Some(Uri(s"${openIdConfig}revocation")),
      Some(Uri(s"${openIdConfig}logout"))
    )

}
