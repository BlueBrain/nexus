package ch.epfl.bluebrain.nexus.delta.sdk.model.realms

import java.time.Instant
import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, Label, Name, ResourceUris}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.{Encoder, Json}
import io.circe.syntax._

import scala.annotation.nowarn

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends Event {

  /**
    * @return the label of the realm for which this event was emitted
    */
  def label: Label
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

  import GrantType.Camel._
  import ch.epfl.bluebrain.nexus.delta.rdf.instances._

  private val context = ContextValue(contexts.metadata, contexts.realms)

  implicit private[realms] val config: Configuration = Configuration.default
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

  @nowarn("cat=unused")
  implicit def realmEventJsonLdEncoder(implicit base: BaseUri): JsonLdEncoder[RealmEvent] = {
    implicit val subjectEncoder: Encoder[Subject]      = Identity.subjectIdEncoder
    implicit val encoder: Encoder.AsObject[RealmEvent] = Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[RealmEvent]
        .mapJsonObject(_.add("_realmId", ResourceUris.realm(ev.label).accessUri.asJson).remove("keys"))
        .encodeObject(ev)
    }

    JsonLdEncoder.computeFromCirce[RealmEvent](context)
  }

}
