package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.syntax._
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ResourceRef}
import ch.epfl.bluebrain.nexus.delta.sourcing.state.State.GlobalState
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * A realm active state; a realm in an active state can be used to authorize a subject through a token.
  *
  * @param label
  *   the realm label
  * @param rev
  *   the current state revision
  * @param deprecated
  *   the current state deprecation status
  * @param openIdConfig
  *   the openid configuration address
  * @param issuer
  *   the issuer identifier
  * @param keys
  *   the collection of JWK keys in json format
  * @param grantTypes
  *   the supported oauth2 grant types
  * @param logo
  *   an optional logo address
  * @param acceptedAudiences
  *   the optional set of audiences of this realm. JWT with `aud` which do not match this field will be rejected
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
  * @param createdAt
  *   the instant when the resource was created
  * @param createdBy
  *   the subject that created the resource
  * @param updatedAt
  *   the instant when the resource was last updated
  * @param updatedBy
  *   the subject that last updated the resource
  */
final case class RealmState(
    label: Label,
    rev: Int,
    deprecated: Boolean,
    name: Name,
    openIdConfig: Uri,
    issuer: String,
    keys: Set[Json],
    grantTypes: Set[GrantType],
    logo: Option[Uri],
    acceptedAudiences: Option[NonEmptySet[String]],
    authorizationEndpoint: Uri,
    tokenEndpoint: Uri,
    userInfoEndpoint: Uri,
    revocationEndpoint: Option[Uri],
    endSessionEndpoint: Option[Uri],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject
) extends GlobalState {

  private val uris = ResourceUris.realm(label)

  /**
    * @return
    *   the schema reference that realm conforms to
    */
  def schema: ResourceRef = Latest(schemas.realms)

  /**
    * @return
    *   the collection of known types of realm resources
    */
  def types: Set[Iri] = Set(nxv.Realm)

  /**
    * @return
    *   the realm information
    */
  def realm: Realm =
    Realm(
      label = label,
      name = name,
      openIdConfig = openIdConfig,
      issuer = issuer,
      grantTypes = grantTypes,
      logo = logo,
      acceptedAudiences = acceptedAudiences,
      authorizationEndpoint = authorizationEndpoint,
      tokenEndpoint = tokenEndpoint,
      userInfoEndpoint = userInfoEndpoint,
      revocationEndpoint = revocationEndpoint,
      endSessionEndpoint = endSessionEndpoint,
      keys = keys
    )

  /**
    * @return
    *   a resource representation for the realm
    */
  def toResource: RealmResource =
    ResourceF(
      id = uris.relativeAccessUri.toIri,
      uris = uris,
      rev = rev.toLong,
      types = types,
      deprecated = deprecated,
      createdAt = createdAt,
      createdBy = createdBy,
      updatedAt = updatedAt,
      updatedBy = updatedBy,
      schema = schema,
      value = realm
    )
}

object RealmState {
  @nowarn("cat=unused")
  val serializer: Serializer[Label, RealmState] = {
    import GrantType.Camel._
    import ch.epfl.bluebrain.nexus.delta.rdf.instances._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration      = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[RealmState] = deriveConfiguredCodec[RealmState]
    Serializer(_.label)
  }
}
