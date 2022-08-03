package ch.epfl.bluebrain.nexus.delta.sdk.realms.model

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.realms.Realms
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Label}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredCodec, deriveConfiguredEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends GlobalEvent {

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

  @nowarn("cat=unused")
  val serializer: Serializer[Label, RealmEvent] = {
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration      = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[RealmEvent] = deriveConfiguredCodec[RealmEvent]
    Serializer(_.label)
  }

  def sseEncoder(implicit base: BaseUri): SseEncoder[RealmEvent] = new SseEncoder[RealmEvent] {

    override val databaseDecoder: Decoder[RealmEvent] = serializer.codec

    override def entityType: EntityType = Realms.entityType

    override val selectors: Set[Label] = Set(Label.unsafe("realms"))

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[RealmEvent] = {
      val context = ContextValue(contexts.metadata, contexts.realms)

      implicit val config: Configuration = Configuration.default
        .withDiscriminator(keywords.tpe)
        .copy(transformMemberNames = {
          case "label"                 => nxv.label.prefix
          case "rev"                   => nxv.rev.prefix
          case "instant"               => nxv.instant.prefix
          case "subject"               => nxv.eventSubject.prefix
          case "issuer"                => nxv.issuer.prefix
          case "grantTypes"            => nxv.grantTypes.prefix
          case "authorizationEndpoint" => nxv.authorizationEndpoint.prefix
          case "tokenEndpoint"         => nxv.tokenEndpoint.prefix
          case "userInfoEndpoint"      => nxv.userInfoEndpoint.prefix
          case "revocationEndpoint"    => nxv.revocationEndpoint.prefix
          case "endSessionEndpoint"    => nxv.endSessionEndpoint.prefix
          case other                   => other
        })

      implicit val subjectEncoder: Encoder[Subject] = IriEncoder.jsonEncoder[Subject]
      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[RealmEvent]
          .encodeObject(event)
          .remove("keys")
          .add("_realmId", ResourceUris.realm(event.label).accessUri.asJson)
          .add(keywords.context, context.value)
      }
    }
  }
}
