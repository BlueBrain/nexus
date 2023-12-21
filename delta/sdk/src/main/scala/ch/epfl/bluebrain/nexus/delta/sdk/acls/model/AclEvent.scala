package ch.epfl.bluebrain.nexus.delta.sdk.acls.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.acls.Acls
import ch.epfl.bluebrain.nexus.delta.sourcing.Serializer
import ch.epfl.bluebrain.nexus.delta.sourcing.event.Event.GlobalEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}
import io.circe.syntax.EncoderOps
import io.circe.{Codec, Encoder}

import java.time.Instant
import scala.annotation.nowarn

/**
  * Enumeration of ACL event types.
  */
sealed trait AclEvent extends GlobalEvent {

  /**
    * The relative [[Iri]] of the acl
    */
  override def id: Iri = Acls.encodeId(address)

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
    Serializer(Acls.encodeId)
  }

}
