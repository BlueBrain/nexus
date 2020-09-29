package ch.epfl.bluebrain.nexus.delta.sdk.model.acls

import java.time.Instant

import ch.epfl.bluebrain.nexus.delta.sdk.model.Event
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject

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
    * @param address the address for the ACL
    * @param acl     the ACL replaced, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclReplaced(
      address: AclAddress,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL append.
    *
    * @param address the address for the ACL
    * @param acl     the ACL appended, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclAppended(
      address: AclAddress,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

  /**
    * A witness to ACL subtraction.
    *
    * @param address the address for the ACL
    * @param acl     the ACL subtracted, represented as a mapping of identities to permissions
    * @param rev     the revision that this event generated
    * @param instant the instant when this event was recorded
    * @param subject the subject which generated this event
    */
  final case class AclSubtracted(
      address: AclAddress,
      acl: Acl,
      rev: Long,
      instant: Instant,
      subject: Subject
  ) extends AclEvent

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

}
