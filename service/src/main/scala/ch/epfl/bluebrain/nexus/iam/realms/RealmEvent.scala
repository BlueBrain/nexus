package ch.epfl.bluebrain.nexus.iam.realms

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.iam.types.{GrantType, Identity, Label}
import ch.epfl.bluebrain.nexus.rdf.Iri.Url
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.generic.extras.Configuration
import io.circe.{Encoder, Json}

/**
  * Enumeration of Realm event types.
  */
sealed trait RealmEvent extends Product with Serializable {

  /**
    * @return the label of the realm for which this event was emitted
    */
  def id: Label

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
    * @param id                    the label of the realm
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
      id: Label,
      rev: Long,
      name: String,
      openIdConfig: Url,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Url],
      authorizationEndpoint: Url,
      tokenEndpoint: Url,
      userInfoEndpoint: Url,
      revocationEndpoint: Option[Url],
      endSessionEndpoint: Option[Url],
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm update.
    *
    * @param id                    the label of the realm
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
      id: Label,
      rev: Long,
      name: String,
      openIdConfig: Url,
      issuer: String,
      keys: Set[Json],
      grantTypes: Set[GrantType],
      logo: Option[Url],
      authorizationEndpoint: Url,
      tokenEndpoint: Url,
      userInfoEndpoint: Url,
      revocationEndpoint: Option[Url],
      endSessionEndpoint: Option[Url],
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  /**
    * A witness to a realm deprecation.
    *
    * @param id      the label of the realm
    * @param rev     the revision this event generated
    * @param instant the instant when the event was emitted
    * @param subject the subject that performed the action that resulted in emitting this event
    */
  final case class RealmDeprecated(
      id: Label,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends RealmEvent

  object JsonLd {
    import ch.epfl.bluebrain.nexus.iam.types.GrantType.Camel._
    import io.circe.generic.extras.semiauto._

    implicit private[JsonLd] val config: Configuration = Configuration.default
      .withDiscriminator("@type")
      .copy(transformMemberNames = {
        case "id"                    => nxv.label.prefix
        case "rev"                   => nxv.rev.prefix
        case "instant"               => nxv.instant.prefix
        case "subject"               => nxv.eventSubject.prefix
        case "issuer"                => nxv.issuer.prefix
        case "keys"                  => nxv.keys.prefix
        case "grantTypes"            => nxv.grantTypes.prefix
        case "authorizationEndpoint" => nxv.authorizationEndpoint.prefix
        case "tokenEndpoint"         => nxv.tokenEndpoint.prefix
        case "userInfoEndpoint"      => nxv.userInfoEndpoint.prefix
        case "revocationEndpoint"    => nxv.revocationEndpoint.prefix
        case "endSessionEndpoint"    => nxv.endSessionEndpoint.prefix
        case other                   => other
      })

    implicit private[JsonLd] def subjectEncoder(implicit http: HttpConfig): Encoder[Subject] =
      Identity.subjectIdEncoder

    implicit def realmEventEncoder(implicit http: HttpConfig): Encoder[Event] = {
      Encoder.encodeJson.contramap[Event] { ev =>
        deriveConfiguredEncoder[Event]
          .mapJson { json =>
            val id = Json.obj("@id" -> Json.fromString((http.realmsIri + ev.id.value).asUri))
            json
              .deepMerge(id)
              .addContext(iamCtxUri)
              .addContext(resourceCtxUri)
          }
          .apply(ev)
      }
    }
  }
}
