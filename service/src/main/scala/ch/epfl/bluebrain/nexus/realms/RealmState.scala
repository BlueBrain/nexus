package ch.epfl.bluebrain.nexus.realms

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.ResourceF
import ch.epfl.bluebrain.nexus.auth.Identity.Subject
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.realms.RealmState.{Current, Initial}
import io.circe.Json

/**
  * Enumeration of Realm states.
  */
sealed trait RealmState extends Product with Serializable {

  /**
    * @return an optional resource representation for this tate
    */
  def optResource(implicit http: HttpConfig): OptResource = this match {
    case Initial    => None
    case c: Current => Some(c.resource)
  }
}

object RealmState {

  /**
    * Initial state for realms.
    */
  sealed trait Initial extends RealmState

  /**
    * Initial state for realms.
    */
  final case object Initial extends Initial

  /**
    * Enumeration of states for realms that were created.
    */
  sealed trait Current extends RealmState {

    /**
      * @return the realm label
      */
    def id: RealmLabel

    /**
      * @return the current state revision
      */
    def rev: Long

    /**
      * @return the name of the realm
      */
    def name: String

    /**
      * @return the address of the openid configuration
      */
    def openIdConfig: Uri

    /**
      * @return an optional realm logo address
      */
    def logo: Option[Uri]

    /**
      * @return the realm deprecation status
      */
    def deprecated: Boolean

    /**
      * @return the instant when the resource was created
      */
    def createdAt: Instant

    /**
      * @return the subject that created the resource
      */
    def createdBy: Subject

    /**
      * @return the instant when the resource was last updated
      */
    def updatedAt: Instant

    /**
      * @return the subject that last updated the resource
      */
    def updatedBy: Subject

    /**
      * @return the current state in a [[Resource]] representation
      */
    def resource(implicit http: HttpConfig): Resource = this match {
      case s: Active     => s.activeResource.map(Right.apply)
      case s: Deprecated => s.deprecatedResource.map(Left.apply)
    }

    /**
      * @return the current state in a [[ResourceMetadata]] representation
      */
    def resourceMetadata(implicit http: HttpConfig): ResourceMetadata =
      ResourceF(
        id.toUri(http.realmsUri),
        rev,
        types,
        createdAt,
        createdBy,
        updatedAt,
        updatedBy,
        (id, deprecated)
      )
  }

  /**
    * A realm active state; a realm in an active state can be used to authorize a subject through a token.
    *
    * @param id                    the realm label
    * @param rev                   the current state revision
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
  final case class Active(
      id: RealmLabel,
      rev: Long,
      name: String,
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
  ) extends Current {

    override val deprecated: Boolean = false

    /**
      * @return the current state in an [[ActiveRealm]] representation
      */
    def activeRealm: ActiveRealm =
      ActiveRealm(
        id,
        name,
        openIdConfig,
        issuer,
        grantTypes,
        logo,
        authorizationEndpoint,
        tokenEndpoint,
        userInfoEndpoint,
        revocationEndpoint,
        endSessionEndpoint,
        keys
      )

    /**
      * @return the current state in a [[ResourceF]] representation
      */
    def activeResource(implicit http: HttpConfig): ResourceF[ActiveRealm] =
      resourceMetadata.map(_ => activeRealm)
  }

  /**
    * A realm deprecated state; a realm in a deprecated state cannot be used to authorize a subject through a token and
    * should be generally disregarded by clients.
    *
    * @param id           the realm label
    * @param rev          the current state revision
    * @param openIdConfig the openid configuration address
    * @param logo         an optional logo address
    * @param createdAt    the instant when the resource was created
    * @param createdBy    the subject that created the resource
    * @param updatedAt    the instant when the resource was last updated
    * @param updatedBy    the subject that last updated the resource
    */
  final case class Deprecated(
      id: RealmLabel,
      rev: Long,
      name: String,
      openIdConfig: Uri,
      logo: Option[Uri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ) extends Current {

    override val deprecated: Boolean = true

    /**
      * @return the current state in a [[DeprecatedRealm]] representation
      */
    def deprecatedRealm: DeprecatedRealm =
      DeprecatedRealm(id, name, openIdConfig, logo)

    /**
      * @return the current state in a [[ResourceF]] representation
      */
    def deprecatedResource(implicit http: HttpConfig): ResourceF[DeprecatedRealm] =
      resourceMetadata.map(_ => deprecatedRealm)
  }
}
