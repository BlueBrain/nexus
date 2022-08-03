package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.{contexts, nxv}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.JsonLdContext.keywords
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sdk.implicits._
import ch.epfl.bluebrain.nexus.delta.sdk.jsonld.IriEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceUris}
import ch.epfl.bluebrain.nexus.delta.sdk.sse.SseEncoder
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, Identity, Label}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Decoder, Encoder, Json}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends GlobalEvent {

  /**
    * @return
    *   the address for the ACL
    */
  def address: AclAddress

}

object AclEvent {

  /**
    * A witness to ACL replace.
    *
    * @param acl
    *   the ACL replaced, represented as a mapping of identities to permissions
    * @param rev
    *   the revision that this event generated
    * @param instant
    *   the instant when this event was recorded
    * @param subject
    *   the subject which generated this event
    */
  final case class AclReplaced(
      acl: Acl,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL append.
    *
    * @param acl
    *   the ACL appended, represented as a mapping of identities to permissions
    * @param rev
    *   the revision that this event generated
    * @param instant
    *   the instant when this event was recorded
    * @param subject
    *   the subject which generated this event
    */
  final case class AclAppended(
      acl: Acl,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL subtraction.
    *
    * @param acl
    *   the ACL subtracted, represented as a mapping of identities to permissions
    * @param rev
    *   the revision that this event generated
    * @param instant
    *   the instant when this event was recorded
    * @param subject
    *   the subject which generated this event
    */
  final case class AclSubtracted(
      acl: Acl,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends AclEvent {
    override val address: AclAddress = acl.address
  }

  /**
    * A witness to ACL deletion.
    *
    * @param address
    *   the address for the ACL
    * @param rev
    *   the revision that this event generated
    * @param instant
    *   the instant when this event was recorded
    * @param subject
    *   the subject which generated this event
    */
  final case class AclDeleted(
      address: AclAddress,
      rev: Int,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  @nowarn("cat=unused")
  val serializer: Serializer[AclAddress, AclEvent] = {
    import Acl.Database._
    import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Database._
    implicit val configuration: Configuration    = Serializer.circeConfiguration
    implicit val coder: Codec.AsObject[AclEvent] = Codec.AsObject.from(
      deriveConfiguredDecoder[AclEvent],
      Encoder.AsObject.instance { ev =>
        deriveConfiguredEncoder[AclEvent].mapJsonObject(_.add("address", ev.address.asJson)).encodeObject(ev)
      }
    )
    Serializer(_.address)
  }

  def sseEncoder(implicit base: BaseUri): SseEncoder[AclEvent] = new SseEncoder[AclEvent] {

    override val databaseDecoder: Decoder[AclEvent] = serializer.codec

    override def entityType: EntityType = Acls.entityType

    override val selectors: Set[Label] = Set(Label.unsafe("acls"))

    @nowarn("cat=unused")
    override val sseEncoder: Encoder.AsObject[AclEvent] = {
      val context                        = ContextValue(contexts.metadata, contexts.acls)
      implicit val config: Configuration = Configuration.default
        .withDiscriminator(keywords.tpe)
        .copy(transformMemberNames = {
          case "address" => nxv.path.prefix
          case "instant" => nxv.instant.prefix
          case "subject" => nxv.eventSubject.prefix
          case "rev"     => nxv.rev.prefix
          case other     => other
        })

      implicit val subjectEncoder: Encoder[Subject] = IriEncoder.jsonEncoder[Subject]

      implicit val aclEncoder: Encoder[Acl] =
        Encoder.instance { acl =>
          Json.fromValues(
            acl.value.map { case (identity: Identity, permissions) =>
              Json.obj("identity" -> identity.asJson, "permissions" -> permissions.asJson)
            }
          )
        }

      Encoder.encodeJsonObject.contramapObject { event =>
        deriveConfiguredEncoder[AclEvent]
          .encodeObject(event)
          .add("_aclId", ResourceUris.acl(event.address).accessUri.asJson)
          .add("_path", event.address.asJson)
          .add(keywords.context, context.value)
      }
    }
  }
}
