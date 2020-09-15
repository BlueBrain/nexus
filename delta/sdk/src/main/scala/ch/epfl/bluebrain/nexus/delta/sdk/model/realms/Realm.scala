package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import io.circe.Json

/**
  * A realm representation.
  *
  * @param label                 the label of the realm
  * @param name                  the name of the realm
  * @param openIdConfig          the address of the openid configuration
  * @param issuer                an identifier for the issuer
  * @param grantTypes            the supported grant types of the realm
  * @param logo                  an optional logo address
  * @param authorizationEndpoint the authorization endpoint
  * @param tokenEndpoint         the token endpoint
  * @param userInfoEndpoint      the user info endpoint
  * @param revocationEndpoint    an optional revocation endpoint
  * @param endSessionEndpoint    an optional end session endpoint
  * @param keys                  the set of JWK keys as specified by rfc 7517 (https://tools.ietf.org/html/rfc7517)
  */
final case class Realm(
    label: Label,
    name: Name,
    openIdConfig: Uri,
    issuer: String,
    grantTypes: Set[GrantType],
    logo: Option[Uri],
    authorizationEndpoint: Uri,
    tokenEndpoint: Uri,
    userInfoEndpoint: Uri,
    revocationEndpoint: Option[Uri],
    endSessionEndpoint: Option[Uri],
    keys: Set[Json]
)
