package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import akka.http.scaladsl.model.Uri
import cats.data.NonEmptySet
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredCodec
import io.circe.{Codec, Json}

import java.time.Instant

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends GlobalEvent {

  /**
    * The relative [[Iri]] of the realm
    */
  def id: Iri = Realms.encodeId(label)

  /**
    * @return
    *   the label of the realm for which this event was emitted
    */
  def label: Label

}

object RealmEvent {

  /**
    * A witness to a realm creation.
    *
    * @param label
    *   the label of the realm
    * @param rev
    *   the revision this event generated
    * @param name
    *   the name of the realm
    * @param openIdConfig
    *   the address of the openid configuration
    * @param issuer
    *   the issuer identifier
    * @param keys
    *   the collection of keys
    * @param grantTypes
    *   the types of OAuth2 grants supported
    * @param logo
    *   an optional address for a logo
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
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class RealmCreated(
      label: Label,
      rev: Int,
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
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  object RealmCreated {

    def apply(
        label: Label,
        name: Name,
        openIdConfig: Uri,
        logo: Option[Uri],
        acceptedAudiences: Option[NonEmptySet[String]],
        wk: WellKnown,
        instant: Instant,
        subject: Subject
    ): RealmCreated =
      RealmCreated(
        label,
        1,
        name,
        openIdConfig,
        wk.issuer,
        wk.keys,
        wk.grantTypes,
        logo,
        acceptedAudiences,
        wk.authorizationEndpoint,
        wk.tokenEndpoint,
        wk.userInfoEndpoint,
        wk.revocationEndpoint,
        wk.endSessionEndpoint,
        instant,
        subject
      )

  }

  /**
    * A witness to a realm update.
    *
    * @param label
    *   the label of the realm
    * @param rev
    *   the revision this event generated
    * @param name
    *   the name of the realm
    * @param openIdConfig
    *   the address of the openid configuration
    * @param issuer
    *   the issuer identifier
    * @param keys
    *   the collection of keys
    * @param grantTypes
    *   the types of OAuth2 grants supported
    * @param logo
    *   an optional address for a logo
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
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class RealmUpdated(
      label: Label,
      rev: Int,
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
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  object RealmUpdated {

    def apply(
        label: Label,
        rev: Int,
        name: Name,
        openIdConfig: Uri,
        logo: Option[Uri],
        acceptedAudiences: Option[NonEmptySet[String]],
        wk: WellKnown,
        instant: Instant,
        subject: Subject
    ): RealmUpdated =
      RealmUpdated(
        label,
        rev,
        name,
        openIdConfig,
        wk.issuer,
        wk.keys,
        wk.grantTypes,
        logo,
        acceptedAudiences,
        wk.authorizationEndpoint,
        wk.tokenEndpoint,
        wk.userInfoEndpoint,
        wk.revocationEndpoint,
        wk.endSessionEndpoint,
        instant,
        subject
      )

  }

  /**
    * A witness to a realm deprecation.
    *
    * @param label
    *   the label of the realm
    * @param rev
    *   the revision this event generated
    * @param instant
    *   the instant when the event was emitted
    * @param subject
    *   the subject that performed the action that resulted in emitting this event
    */
  final case class RealmDeprecated(
      label: Label,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  import GrantType.Camel._
  import ch.epfl.bluebrain.nexus.delta.rdf.instances._

  val serializer: Serializer[Label, RealmEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration      = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[RealmEvent] = deriveConfiguredCodec[RealmEvent]
    Serializer(Realms.encodeId)
  }
}
