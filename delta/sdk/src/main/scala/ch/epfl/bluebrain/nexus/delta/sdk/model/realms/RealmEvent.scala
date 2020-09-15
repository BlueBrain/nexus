package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.sdk.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Label, Name}
import io.circe.Json

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends Product with Serializable {

  /**
    * @return the label of the realm for which this event was emitted
    */
  def label: Label

  /**
    * @return the revision this event generated
    */
  def rev: Long

  /**
    * @return the instant when the event was emitted
    */
  def instant: Instant

  /**
    * @return the subject that performed the action that resulted in emitting this event
    */
  def subject: Subject
}

object RealmEvent {

  /**
    * A witness to a realm creation.
    *
    * @param label                 the label of the realm
    * @param rev                   the revision this event generated
    * @param name                  the name of the realm
    * @param openIdConfig          the address of the openid configuration
    * @param issuer                the issuer identifier
    * @param keys                  the collection of keys
    * @param grantTypes            the types of OAuth2 grants supported
    * @param logo                  an optional address for a logo
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param instant               the instant when the event was emitted
    * @param subject               the subject that performed the action that resulted in emitting this event
    */
  final case class RealmCreated(
      label: Label,
      rev: Long,
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
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm update.
    *
    * @param label                 the label of the realm
    * @param rev                   the revision this event generated
    * @param name                  the name of the realm
    * @param openIdConfig          the address of the openid configuration
    * @param issuer                the issuer identifier
    * @param keys                  the collection of keys
    * @param grantTypes            the types of OAuth2 grants supported
    * @param logo                  an optional address for a logo
    * @param authorizationEndpoint the authorization endpoint
    * @param tokenEndpoint         the token endpoint
    * @param userInfoEndpoint      the user info endpoint
    * @param revocationEndpoint    an optional revocation endpoint
    * @param endSessionEndpoint    an optional end session endpoint
    * @param instant               the instant when the event was emitted
    * @param subject               the subject that performed the action that resulted in emitting this event
    */
  final case class RealmUpdated(
      label: Label,
      rev: Long,
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
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm deprecation.
    *
    * @param label   the label of the realm
    * @param rev     the revision this event generated
    * @param instant the instant when the event was emitted
    * @param subject the subject that performed the action that resulted in emitting this event
    */
  final case class RealmDeprecated(
      label: Label,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends RealmEvent
}
