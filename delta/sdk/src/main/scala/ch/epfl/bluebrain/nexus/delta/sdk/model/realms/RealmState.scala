package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{nxv, schemas}
import ch.epfl.bluebrain.nexus.delta.sdk.RealmResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceRef.Latest
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Identity, Label, Name, ResourceF}
import io.circe.Json

/**
  * Enumeration of Permissions states.
  */
sealed trait RealmState extends Product with Serializable {

  /**
    * Converts the state into a resource representation.
    */
  def toResource: Option[RealmResource]

}

object RealmState {

  /**
    * Initial state type.
    */
  type Initial = Initial.type

  /**
    * Initial state for realms.
    */
  final case object Initial extends RealmState {

    /**
      * Converts the state into a resource representation.
      */
    override val toResource: Option[RealmResource] = None
  }

  /**
    * A realm active state; a realm in an active state can be used to authorize a subject through a token.
    *
    * @param id                    the realm label
    * @param rev                   the current state revision
    * @param deprecated            the current state deprecation status
    * @param openIdConfig          the openid configuration address
    * @param issuer                the issuer identifier
    * @param keys                  the collection of JWK keys in json format
    * @param grantTypes            the supported oauth2 grant types
    * @param logo                  an optional logo address
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param createdAt             the instant when the resource was created
    * @param createdBy             the subject that created the resource
    * @param updatedAt             the instant when the resource was last updated
    * @param updatedBy             the subject that last updated the resource
    */
  final case class Current(
      id: Label,
      rev: Long,
      deprecated: Boolean,
      name: Name,
      openIdConfig: Uri,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Uri],
      authorizationEndpoint: Uri,
      tokenEndpoint: Uri,
      userInfoEndpoint: Uri,
      revocationEndpoint: Option[Uri],
      endSessionEndpoint: Option[Uri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends RealmState {

    /**
      * Converts the state into a resource representation.
      */
    override def toResource: Option[RealmResource] =
      Some(
        ResourceF(
          id = id,
          rev = rev,
          types = Set(nxv.Realm),
          deprecated = deprecated,
          createdAt = Instant.EPOCH,
          createdBy = Identity.Anonymous,
          updatedAt = Instant.EPOCH,
          updatedBy = Identity.Anonymous,
          schema = Latest(schemas.realms),
          value = Realm(
            id = id,
            name = name,
            openIdConfig = openIdConfig,
            issuer = issuer,
            grantTypes = grantTypes,
            logo = logo,
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            userInfoEndpoint = userInfoEndpoint,
            revocationEndpoint = revocationEndpoint,
            endSessionEndpoint = endSessionEndpoint,
            keys = keys
          )
        )
      )
  }

}
