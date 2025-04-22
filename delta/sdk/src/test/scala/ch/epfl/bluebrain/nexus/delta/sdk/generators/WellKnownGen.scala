package ch.epfl.bluebrain.nexus.delta.sdk.generators

import ch.epfl.bluebrain.nexus.delta.sdk.realms.model
import ch.epfl.bluebrain.nexus.delta.sdk.realms.model.{GrantType, WellKnown}
import ch.epfl.bluebrain.nexus.testkit.CirceLiteral
import org.http4s.Uri

object WellKnownGen extends CirceLiteral {

  def create(
      issuer: String,
      grantTypes: Set[GrantType] = Set(GrantType.AuthorizationCode, GrantType.Implicit)
  ): (Uri, WellKnown) = {
    val baseUri = Uri.unsafeFromString(s"https://localhost/auth/$issuer/protocol/openid-connect/")
    baseUri -> createFromUri(baseUri, issuer, grantTypes)
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
      openIdConfig.addPath("auth"),
      openIdConfig.addPath("token"),
      openIdConfig.addPath("userinfo"),
      Some(openIdConfig.addPath("revocation")),
      Some(openIdConfig.addPath("logout"))
    )

}
