package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import io.circe.Json
import org.http4s.Uri

/**
  * Data type that represents the required well known configuration for an OIDC provider.
  *
  * @param issuer
  *   the issuer identifier
  * @param grantTypes
  *   the collection of supported grant types
  * @param keys
  *   the collection of keys
  * @param authorizationEndpoint
  *   the authorization endpoint
  * @param tokenEndpoint
  *   the token endpoint
  * @param userInfoEndpoint
  *   the user info endpoint
  * @param revocationEndpoint
  *   an optional revocation endpoint
  * @param endSessionEndpoint
  *   an optional end session endpoint
  */
final case class WellKnown(
    issuer: String,
    grantTypes: Set[GrantType],
    keys: Set[Json],
    authorizationEndpoint: Uri,
    tokenEndpoint: Uri,
    userInfoEndpoint: Uri,
    revocationEndpoint: Option[Uri],
    endSessionEndpoint: Option[Uri]
)
