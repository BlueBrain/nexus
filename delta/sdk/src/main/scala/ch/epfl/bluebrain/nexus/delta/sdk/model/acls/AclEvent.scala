package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, Event, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder
import io.circe.syntax.EncoderOps
import io.circe.{Encoder, Json}

import scala.annotation.nowarn

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends Event {

  /**
    * @return the address for the ACL
    */
  def address: AclAddress
}

object AclEvent {

  /**
    * A witness to ACL replace.
    *
    * @param acl     the ACL replaced, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclReplaced(
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL append.
    *
    * @param acl     the ACL appended, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclAppended(
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL subtraction.
    *
    * @param acl     the ACL subtracted, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclSubtracted(
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL deletion.
    *
    * @param address the address for the ACL
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclDeleted(
      address: AclAddress,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  private val context = ContextValue(contexts.metadata, contexts.acls)

  @nowarn("cat=unused")
  implicit def aclEventJsonLdEncoder(implicit baseUri: BaseUri): JsonLdEncoder[AclEvent] = {
    implicit val subjectEncoder: Encoder[Subject] = Identity.subjectIdEncoder

    implicit val config: Configuration = Configuration.default
      .withDiscriminator(keywords.tpe)
      .copy(transformMemberNames = {
        case "address" => nxv.path.prefix
        case "instant" => nxv.instant.prefix
        case "subject" => nxv.eventSubject.prefix
        case "rev"     => nxv.rev.prefix
        case other     => other
      })

    implicit val aclEncoder: Encoder[Acl] =
      Encoder.instance { acl =>
        Json.fromValues(
          acl.value.map { case (identity: Identity, permissions) =>
            Json.obj("identity" -> identity.asJson, "permissions" -> permissions.asJson)
          }
        )
      }

    implicit val encoder: Encoder.AsObject[AclEvent] = Encoder.AsObject.instance { ev =>
      deriveConfiguredEncoder[AclEvent]
        .mapJsonObject(_.add("_aclId", ResourceUris.acl(ev.address).accessUri.asJson).add("_path", ev.address.asJson))
        .encodeObject(ev)
    }

    JsonLdEncoder.computeFromCirce[AclEvent](context)
  }
}
