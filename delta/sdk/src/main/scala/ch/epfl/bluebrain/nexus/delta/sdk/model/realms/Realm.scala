package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.realms.Realm.Metadata
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import io.circe._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

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
) {

  /**
    * @return [[Realm]] metadata
    */
  def metadata: Metadata = Metadata(label)
}

object Realm {

  /**
    * Realm metadata.
    *
    * @param label  the label of the realm
    */
  final case class Metadata(label: Label)
  import GrantType.Camel._
  import ch.epfl.bluebrain.nexus.delta.rdf.instances._

  implicit private[Realm] val config: Configuration = Configuration.default.copy(transformMemberNames = {
    case "authorizationEndpoint" => nxv.authorizationEndpoint.prefix
    case "endSessionEndpoint"    => nxv.endSessionEndpoint.prefix
    case "grantTypes"            => nxv.grantTypes.prefix
    case "issuer"                => nxv.issuer.prefix
    case "label"                 => nxv.label.prefix
    case "revocationEndpoint"    => nxv.revocationEndpoint.prefix
    case "tokenEndpoint"         => nxv.tokenEndpoint.prefix
    case "userInfoEndpoint"      => nxv.userInfoEndpoint.prefix
    case other                   => other
  })

  implicit val realmEncoder: Encoder.AsObject[Realm] = {
    deriveConfiguredEncoder[Realm].mapJsonObject(
      _.remove("keys")
    )
  }

  val context: ContextValue                             = ContextValue(contexts.realms)
  implicit val realmJsonLdEncoder: JsonLdEncoder[Realm] =
    JsonLdEncoder.computeFromCirce(context)

  implicit private val realmMetadataEncoder: Encoder.AsObject[Metadata] = deriveConfiguredEncoder[Metadata]
  implicit val realmMetadataJsonLdEncoder: JsonLdEncoder[Metadata]      =
    JsonLdEncoder.computeFromCirce(ContextValue(contexts.metadata))
}
